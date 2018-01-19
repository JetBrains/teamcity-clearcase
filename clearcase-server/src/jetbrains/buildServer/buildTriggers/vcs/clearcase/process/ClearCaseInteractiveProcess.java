/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Constants;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * @author Maxim.Manuylov
 *         Date: 10/19/11
 */
public class ClearCaseInteractiveProcess extends InteractiveProcess {
  private static final Logger LOG = Logger.getLogger(ClearCaseInteractiveProcess.class);
  @NotNull public static final String READ_TIMEOUT_PROPERTY_NAME = "clearcase.cleartool.read.timeout.seconds";

  private Process myProcess;
  private String myWorkingDirectory;
  private final LinkedList<String> myLastExecutedCommand = new LinkedList<String>();
  private ILineFilter myErrorFilter;
  private String myLastOutput;

  public Process getProcess() {
    return myProcess;
  }

  public String getWorkingDirectory() {
    return myWorkingDirectory;
  }

  //just for testing purpose
  public ClearCaseInteractiveProcess(final InputStream errorStream) {
    super(null, errorStream, null);
  }

  public ClearCaseInteractiveProcess(final String workingDirectory, final Process process) {
    super(process.getInputStream(), process.getErrorStream(), process.getOutputStream());
    myProcess = process;
    myWorkingDirectory = workingDirectory;
    LOG.debug(String.format("Creating new ClearCaseInteractiveProcess in '%s'...", workingDirectory));
    LOG.trace(String.format("Creating new ClearCaseInteractiveProcess in '%s' (hash: %d)...", workingDirectory, hashCode()), new Exception());
  }

  @Override
  public void destroy() {
    LOG.debug("Destroying process...");
    LOG.trace(String.format("Destroying process (hash: %d)...", hashCode()), new Exception());
    super.destroy();
  }

  /**
   * @return true if the Process is still running
   */
  public boolean isRunning() {
    try {
      myProcess.exitValue();
      return false;
    } catch (Throwable t) {
      return true;
    }
  }

  @Override
  protected void execute(@NotNull final String[] args) throws IOException {
    super.execute(args);
    final String commandLineString = createCommandLineString(args);
    LOG.debug("interactive execute: " + commandLineString);
    //cache last
    myLastExecutedCommand.add(commandLineString);
    if (myLastExecutedCommand.size() > 5) {
      myLastExecutedCommand.removeFirst();
    }
  }

  @Override
  protected int getReadTimeoutSeconds() {
    return TeamCityProperties.getInteger(READ_TIMEOUT_PROPERTY_NAME, 300); // 5 minutes
  }

  @NotNull
  @Override
  protected String createCommandLineString(@NotNull final String[] args) {
    final StringBuffer commandLine = new StringBuffer();
    commandLine.append("cleartool");
    for (final String arg : args) {
      commandLine.append(' ');
      if (arg.contains(" ")) {
        commandLine.append("'").append(arg).append("'");
      }
      else {
        commandLine.append(arg);
      }
    }
    return commandLine.toString();
  }

  @NotNull private static final Pattern END_OF_COMMAND = Pattern.compile("(.*)Command (\\d*) returned status (\\d*)(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  @Override
  protected boolean isEndOfCommandOutput(final String line, final String[] params) throws IOException {
    final Matcher matcher = END_OF_COMMAND.matcher(line);
    if (matcher.matches()) {
      final String group = matcher.group(1);
      myLastOutput = group.length() > 0 ? group : null; //keep the informational output
      final String retCode = matcher.group(3);
      if (!"0".equals(retCode)) {
        final String errorMessage = readError();
        //check there is any message(we can ignore kind of error)
        if (errorMessage.trim().length() > 0) {
          throw new IOException(
            new StringBuilder("Error executing ").append(Arrays.toString(params)).append(": ").append(errorMessage).toString());
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Use the method for getting last output before 'end-of-command' marker.
   * Output will be discarded next to the method invocation
   *
   * @see http://youtrack.jetbrains.net/issue/TW-17141
   */
  @Override
  protected String getLastOutput() {
    final String out = myLastOutput;
    myLastOutput = null;
    return out;
  }

  @Override
  protected void forceDestroy() {
    try {
      final int retCode = myProcess.exitValue();
      LOG.debug(String.format("Destroing Process has already exited with code (%d): %s", retCode, myLastExecutedCommand));
    }
    catch (final IllegalThreadStateException e) {
      LOG.debug(String.format("Destroing Process is still running. Try to waiting for correct termination: %s", myLastExecutedCommand));
      myProcess.destroy();
    }
  }

  @Override
  protected void quit() throws IOException {
    execute(new String[]{"quit"});
  }

  public void copyFileContentTo(final @NotNull String versionFqn, final @NotNull String destFileFqn) throws IOException, VcsException {
    final InputStream input = executeAndReturnProcessInput(new String[]{"get", "-to", destFileFqn, versionFqn});
    input.close();
  }

  @NotNull
  @Override
  public synchronized InputStream executeAndReturnProcessInput(@NotNull final String[] params) throws IOException {
    try {
      return super.executeAndReturnProcessInput(params);
    }
    catch (final IOException ioe) {
      return handleError(ioe);
    }
  }

  private InputStream handleError(final IOException ioe) throws IOException {
    try {
      //check the process is alive and recreate if not so
      final int retCode = getProcess().exitValue();
      LOG.debug(String.format("Interactive process terminated with code '%d'", retCode));
    }
    catch (final IllegalThreadStateException ite) {
      //process is still running. do not trap IOException
      if (isWaitingForUserInput(ioe)) {
        LOG.debug(String.format("Interrupting process for '%s'", getWorkingDirectory()));
        getProcess().getOutputStream().close();
        getProcess().destroy();
        LOG.debug(String.format("Process for '%s' interrupted", getWorkingDirectory()));
      }
    }
    throw ioe;
  }

  @NotNull
  @Override
  protected ILineFilter getErrorFilter() {
    if (myErrorFilter == null) {
      myErrorFilter = new ClearCaseErrorFilter(ClearCaseSupport.getDefault().getIgnoreErrorPatterns());
    }
    return myErrorFilter;
  }

  boolean isWaitingForUserInput(final IOException ioe) {
    if (ioe.getMessage().contains("A snapshot view update is in progress") ||
        ioe.getMessage().contains("An update is already in progress")) {
      LOG.debug(String.format("Waiting for input detected: %s", ioe.getMessage()));
      return true;
    }
    return false;
  }

  private final class ClearCaseErrorFilter implements ILineFilter {
    static final String WARN_LOG_MESSAGE_PATTERN = "Error was detected but rejected by filter: %s";

    private Pattern[] myIgnoreErrorPatterns = new Pattern[0];

    protected ClearCaseErrorFilter(final Pattern... ignorePatterns) {
      if (ignorePatterns.length > 0) {
        myIgnoreErrorPatterns = ignorePatterns;
      }
    }

    @NotNull
    public String apply(final @NotNull String line) {
      for (final Pattern pattern : myIgnoreErrorPatterns) {
        final Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
          LOG.warn(String.format(WARN_LOG_MESSAGE_PATTERN, line));
          return Constants.EMPTY;
        }
      }
      return line;
    }
  }
}
