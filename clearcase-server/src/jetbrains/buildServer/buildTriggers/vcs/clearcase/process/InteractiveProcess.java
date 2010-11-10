/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import jetbrains.buildServer.serverSide.TeamCityProperties;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.VcsException;

public abstract class InteractiveProcess implements InteractiveProcessFacade {
  private final InputStream myInput;
  private final OutputStream myOutput;
  private static final int ERROR_READING_SLEEP_MILLIS = TeamCityProperties.getInteger("clearcase.error.reading.sleep", 100);

  public InteractiveProcess(final InputStream inputStream, final OutputStream outputStream) {
    myInput = inputStream;
    myOutput = outputStream;
  }

  public void destroy() throws IOException {
    try {
      myInput.close();
      executeQuitCommand();
      myOutput.close();
    } finally {
      destroyOSProcess();
    }
  }

  protected abstract void destroyOSProcess();

  protected void executeQuitCommand() throws IOException {
    
  }

  public InputStream executeAndReturnProcessInput(final String[] params) throws IOException {
  	cleanStreams(myInput, getErrorStream()); //discard unread bytes produced by previous command to prevent phantom errors appeariance   
    execute(params);
    try {
      return readFromProcessInput(params);
    } catch (VcsException e) {
      throw new IOException(e.getLocalizedMessage());
    }
  }

  protected void execute(String[] args) throws IOException {
    for (String arg : args) {
      myOutput.write(' ');
      if (arg.contains(" ")) {
        myOutput.write('"');
        myOutput.write(arg.getBytes());
        myOutput.write('"');
      } else {
        myOutput.write(arg.getBytes());
      }

    }
    myOutput.write('\n');
    myOutput.flush();
  }

  private InputStream readFromProcessInput(final String[] params) throws IOException, VcsException {
    while (true) {
      if (myInput.available() > 0) break;
      if (getErrorStream().available() > 0) {
        throw new VcsException(readError());
      }      
    }
    final File tempFile = FileUtil.createTempFile("cc", "execution");
    try {
      final FileOutputStream fileOutput = new FileOutputStream(tempFile);
      try {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(myInput));
  
        String line;
        while ((line = reader.readLine()) != null) {
          lineRead(line);
          if (isEndOfCommandOutput(line, params)) {
            break;
          }
          fileOutput.write(line.getBytes());
          fileOutput.write('\n');
        }
        fileOutput.flush();
      } finally {
        fileOutput.close();
      }
    } catch (IOException e) {
      FileUtil.delete(tempFile);
      throw e;
    }

    final FileInputStream fileInput = new FileInputStream(tempFile);

    return new InputStream() {
      public int read() throws IOException {
        return fileInput.read();
      }


      public void close() throws IOException {
        fileInput.close();
        FileUtil.delete(tempFile);
      }
    };
  }

  private void cleanStreams(InputStream... streams) throws IOException {
    for (final InputStream stream : streams) {
      if (stream.available() > 0) {
        while (stream.read() != -1) {
          if (stream.available() == 0) {
            break;
          }
        }
      }
    }
  }

  protected abstract boolean isEndOfCommandOutput(final String line, final String[] params) throws IOException;

  protected void lineRead(final String line) {
  }

  protected String readError() throws IOException {
    final InputStream errorStream = getErrorStream();
    final StringBuffer result = new StringBuffer();

    int available = errorStream.available();

    do {
      final byte[] read = new byte[available];
      //noinspection ResultOfMethodCallIgnored
      errorStream.read(read);
      result.append(new String(read));
      try {
        Thread.sleep(ERROR_READING_SLEEP_MILLIS);
      } catch (InterruptedException e) {
        //ignore
      }
      available = errorStream.available();
    } while (available > 0);

    return result.toString();
  }

  protected abstract InputStream getErrorStream();
}
