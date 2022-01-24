/*
 * Copyright 2000-2022 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.buildTriggers.vcs.clearcase.process;

import com.intellij.openapi.diagnostic.Logger;
import java.io.*;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class InteractiveProcess implements InteractiveProcessFacade {
  @NotNull private static final Logger LOG = Logger.getInstance(InteractiveProcess.class.getName());
  private static final int ERROR_READING_SLEEP_MILLIS = TeamCityProperties.getInteger("clearcase.error.reading.sleep", 100);

  @Nullable private final InputStream myInput;
  @Nullable private final InputStream myError;
  @Nullable private final OutputStream myOutput;

  public InteractiveProcess(@Nullable final InputStream inputStream,
                            @Nullable final InputStream errorStream,
                            @Nullable final OutputStream outputStream) {
    myInput = inputStream;
    myError = errorStream;
    myOutput = outputStream;
  }

  public void destroy() {
    try {
      quit();
      cleanStreams();
      
      if (myInput != null) {
        myInput.close();
      }

      if (myError != null) {
        myError.close();
      }

      if (myOutput != null) {
        myOutput.close();
      }
    }
    catch (final IOException e) {
      LOG.warnAndDebugDetails("Failed to destroy process", e);
    }
    finally {
      forceDestroy();
    }
  }

  protected abstract void quit() throws IOException;

  protected abstract void forceDestroy();

  @NotNull
  public synchronized InputStream executeAndReturnProcessInput(@NotNull final String[] params) throws IOException {
    cleanStreams();
    execute(params);
    try {
      return readFromProcessInput(params);
    }
    catch (final VcsException e) {
      throw new IOException(e.getMessage());
    }
  }

  private void cleanStreams() throws IOException {
    doCleanStreams(myInput, myError); //discard unread bytes produced by previous command to prevent phantom errors appeariance
  }

  protected void execute(@NotNull final String[] args) throws IOException {
    if (myOutput == null) return;
    for (final String arg : args) {
      myOutput.write(' ');
      // file path must be quoted for interactive execution if it contains single ' (see http://devnet.jetbrains.net/thread/292758 for details)
      myOutput.write('"');
      myOutput.write(arg.getBytes());
      myOutput.write('"');
    }
    myOutput.write('\n');
    myOutput.flush();
  }

  @NotNull
  private InputStream readFromProcessInput(@NotNull final String[] params) throws IOException, VcsException {
    if (myInput == null || myError == null) {
      return new ByteArrayInputStream("".getBytes());
    }

    final int readTimeoutSeconds = getReadTimeoutSeconds();
    final long deadline = System.currentTimeMillis() + readTimeoutSeconds * 1000;

    while (true) {
      if (myInput.available() > 0) break;
      if (myError.available() > 0) {
        final String errorMesage = readError();
        if (errorMesage.trim().length() > 0) {
          throw new VcsException(errorMesage);
        }
      }
      checkTimeoutAndSleep(deadline, readTimeoutSeconds, params);
    }

    final BufferedReader reader = new BufferedReader(new InputStreamReader(myInput));
    final StringBuilder buffer = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (isEndOfCommandOutput(line, params)) {
        final String theRest = getLastOutput();
        if (theRest != null) {
          buffer.append(theRest).append("\n");
        }
        break;
      }
      buffer.append(line).append("\n");
    }
    final String response = buffer.toString();
    if (LOG.isDebugEnabled()) {
      if (params.length == 0 || !"update".equals(params[0]) || TeamCityProperties.getBoolean("clearcase.log.update")) {
        LOG.debug("output line read:\n" + response);
      } else {
        LOG.debug("output was omitted due to its size");
      }
    }
    final ByteArrayInputStream out = new ByteArrayInputStream(response.getBytes());
    return new InputStream() {
      @Override
      public int read() throws IOException {
        return out.read();// fileInput.read();
      }

      @Override
      public void close() throws IOException {
        out.close();
      }
    };
  }

  protected abstract int getReadTimeoutSeconds();

  private void checkTimeoutAndSleep(final long deadline, final int readTimeoutSeconds, @NotNull final String[] params) throws ReadTimeoutException {
    if (System.currentTimeMillis() > deadline) {
      throw new ReadTimeoutException(String.format(
        "No output produced by the process in both stdout and stderr for more then %d seconds (set \"%s\" internal property to change this timeout): %s",
        readTimeoutSeconds,
        ClearCaseInteractiveProcess.READ_TIMEOUT_PROPERTY_NAME,
        createCommandLineString(params)
      ));
    }

    try {
      Thread.sleep(100);
    } catch (final InterruptedException ignore) {}
  }

  @NotNull
  protected abstract String createCommandLineString(@NotNull String[] params);

  private static void doCleanStreams(final InputStream... streams) throws IOException {
    for (final InputStream stream : streams) {
      if (stream != null && stream.available() > 0) {
        while (stream.read() != -1) {
          if (stream.available() == 0) {
            break;
          }
        }
      }
    }
  }

  protected abstract boolean isEndOfCommandOutput(final String line, final String[] params) throws IOException;

  protected String getLastOutput() {
    return null;
  }

  @NotNull
  protected String readError() throws IOException {
    if (myError == null) return "";
    final StringBuffer result = new StringBuffer();
    int available = myError.available();
    if (available > 0) {
      do {
        final byte[] read = new byte[available];
        myError.read(read);
        final String line = new String(read);
        result.append(line);
        try {
          Util.sleep("InteractiveProcess: Error reading", ERROR_READING_SLEEP_MILLIS);
        } catch (InterruptedException e) {
          //ignore
        }
        available = myError.available();
      } while (available > 0);
    }
    return getErrorFilter().apply(result.toString());
  }

  @NotNull
  protected ILineFilter getErrorFilter() {
    return ACCEPT_ALL_FILTER;
  }

  protected static interface ILineFilter {
    @NotNull
    String apply(@NotNull final String line);
  }

  protected static final ILineFilter ACCEPT_ALL_FILTER = new ILineFilter() {
    @NotNull
    public String apply(@NotNull final String line) {
      return line;
    }
  };
}
