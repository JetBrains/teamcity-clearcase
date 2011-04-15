/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.ClearCaseFacade;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.InteractiveProcess;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.InteractiveProcessFacade;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.util.io.StreamUtil;

public class ClearCaseInteractiveProcessPool {

  private static Logger LOG = Logger.getLogger(ClearCaseConnection.class);

  private static ClearCaseInteractiveProcessPool ourDefault;

  private final HashMap<String, ClearCaseInteractiveProcess> ourViewProcesses = new HashMap<String, ClearCaseInteractiveProcess>();

  private ClearCaseFacade myProcessExecutor = new ClearCaseFacade() {

    @SuppressWarnings("serial")
    public ClearCaseInteractiveProcess createProcess(final @NotNull String workingDirectory, final GeneralCommandLine generalCommandLine) throws ExecutionException {
      try {
        final Process osProcess = generalCommandLine.createProcess();
        return ClearCaseInteractiveProcessPool.this.createProcess(workingDirectory, osProcess);
      } catch (ExecutionException e) {
        if (Util.isExecutableNotFoundException(e)) {
          throw new Util.ExecutableNotFoundException(generalCommandLine.getCommandLineString(), e.getMessage()) {
            @Override
            public String getMessage() {
              return Constants.CLIENT_NOT_FOUND_MESSAGE;
            }
          };
        }
        throw e;
      }

    }
  };

  public static class ClearCaseInteractiveProcess extends InteractiveProcess {

    private final class ClearCaseErrorFilter implements ILineFilter {

      static final String WARN_LOG_MESSAGE_PATTERN = "Error was detected but rejected by filter: %s";

      private Pattern[] myIgnoreErrorPatterns = new Pattern[0];

      protected ClearCaseErrorFilter(final Pattern... ignorePatterns) {
        if (ignorePatterns.length > 0) {
          myIgnoreErrorPatterns = ignorePatterns;
        }
      }

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

    private final Process myProcess;
    private LinkedList<String> myLastExecutedCommand = new LinkedList<String>();
    private ILineFilter myErrorFilter;
    private String myWorkingDirectory;

    public Process getProcess() {
      return myProcess;
    }
    
    public String getWorkingDirrectory() {
      return myWorkingDirectory;
    }

    public void destroy() {
      //do nothing
    }

    public ClearCaseInteractiveProcess(final String workingDirectory, final Process process) {
      super(process.getInputStream(), process.getOutputStream());
      myProcess = process;
      myWorkingDirectory = workingDirectory;
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
    protected void execute(final String[] args) throws IOException {
      super.execute(args);
      final StringBuffer commandLine = new StringBuffer();
      commandLine.append("cleartool");
      for (String arg : args) {
        commandLine.append(' ');
        if (arg.contains(" ")) {
          commandLine.append("'").append(arg).append("'");
        } else {
          commandLine.append(arg);
        }

      }
      LOG.debug("interactive execute: " + commandLine.toString());
      //cache last
      myLastExecutedCommand.add(commandLine.toString());
      if (myLastExecutedCommand.size() > 5) {
        myLastExecutedCommand.removeFirst();
      }
    }

    @Override
    protected boolean isEndOfCommandOutput(final String line, final String[] params) throws IOException {
      if (line.startsWith("Command ")) {
        final String restStr = " returned status ";
        final int statusPos = line.indexOf(restStr);
        if (statusPos != -1) {
          //get return status
          final String retCode = line.substring(statusPos + restStr.length(), line.length()).trim();
          if (!"0".equals(retCode)) {
            final String errorMessage = readError();
            //check there is any message(we can ignore kind of error)
            if (errorMessage.trim().length() > 0) {
              throw new IOException(new StringBuilder("Error executing ").append(Arrays.toString(params)).append(": ").append(errorMessage).toString());
            }
          }
          return true;
        }
      }
      return false;
    }

    @Override
    protected InputStream getErrorStream() {
      return myProcess.getErrorStream();
    }

    @Override
    protected void forceDestroy() {
      try {
        final int retCode = myProcess.exitValue();
        LOG.debug(String.format("Destroing Process has already exited with code (%d):", retCode, myLastExecutedCommand));
      } catch (IllegalThreadStateException e) {
        LOG.debug(String.format("Destroing Process is still running. Try to waiting for correct termination: %s", myLastExecutedCommand));
        try {
          myProcess.waitFor();
          forceDestroy();
        } catch (InterruptedException e1) {
          LOG.debug(String.format("Enforce low-level Process terminating. Last commands: %s", myLastExecutedCommand));
          myProcess.destroy();
        }

      }
    }

    @Override
    protected void quit() throws IOException {
      //      super.executeQuitCommand();
      execute(new String[] { "quit" });
    }

    public void copyFileContentTo(final @NotNull String versionFqn, final @NotNull String destFileFqn) throws IOException, VcsException {
      final InputStream input = executeAndReturnProcessInput(new String[] { "get", "-to", destFileFqn, versionFqn });
      input.close();
    }

    /**
     * the most graceful termination
     */
    private void shutdown() {
      super.destroy();
    }

    @Override
    public synchronized InputStream executeAndReturnProcessInput(String[] params) throws IOException {
      try {
        return super.executeAndReturnProcessInput(params);
      } catch (IOException ioe) {
        return handleError(params, ioe);
      }
    }

    @Override
    protected ILineFilter getErrorFilter() {
      if (myErrorFilter == null) {
        myErrorFilter = new ClearCaseErrorFilter(ClearCaseSupport.getDefault().getIgnoreErrorPatterns());
      }
      return myErrorFilter;
    }

    private InputStream handleError(String[] params, IOException ioe) throws IOException {
      try {
        //check the process is alive and recreate if not so        
        int retCode = getProcess().exitValue();
        LOG.debug(String.format("Interactive Process terminated with code '%d', create new one for the view", retCode));
        final InteractiveProcessFacade newProcess = getDefault().renewProcess(this);
        return newProcess.executeAndReturnProcessInput(params);
      } catch (IllegalThreadStateException ite) {
        //process is still running. do not trap IOException
        throw ioe;
      }
    }

  }

  ClearCaseInteractiveProcessPool() {

  }

  public static ClearCaseInteractiveProcessPool getDefault() {
    if (ourDefault == null) {
      ourDefault = new ClearCaseInteractiveProcessPool();
    }
    return ourDefault;
  }

  /**
   * JUST FOR TESTING PURPOSE!
   */
  public void setProcessExecutor(final @NotNull ClearCaseFacade executor) {
    myProcessExecutor = executor;
  }

  public ClearCaseInteractiveProcess getProcess(final @NotNull VcsRoot root) throws IOException {
    synchronized (ourViewProcesses) {
      final String processKey = getVcsRootProcessKey(root);
      ClearCaseInteractiveProcess cached = ourViewProcesses.get(processKey);
      if (cached == null) {
        //create new
        cached = createProcess(processKey);
        //        cached.addRef();
        ourViewProcesses.put(processKey, cached);
      }
      return cached;
    }
  }

  public ClearCaseInteractiveProcess getProcess(final @NotNull String viewPath) throws IOException {
    //check there already is process for the path and reuse them
    synchronized (ourViewProcesses) {
      for (final Map.Entry<String, ClearCaseInteractiveProcess> processEntry : ourViewProcesses.entrySet()) {
        final String processKey = processEntry.getKey();
        if (Util.isUnderPath(viewPath, processKey)) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Cache hit: '%s'->'%s'", viewPath, processEntry.getKey()));
          }
          return processEntry.getValue();
        }
      }
    }
    //create new 
    return createProcess(viewPath);
  }

  private ClearCaseInteractiveProcess createProcess(final @NotNull String viewPath) throws IOException {
    //create new
    if (LOG.isDebugEnabled()) {
      LOG.debug(String.format("Creating new ClearCaseInteractiveProcess for '%s'...", viewPath));
    }
    final GeneralCommandLine rootDetectionCommandLine = createCommandLine(viewPath, "-status");
    try {
      //have to detect View's root for proper caching
      final ClearCaseInteractiveProcess rootDetectionProcess = (ClearCaseInteractiveProcess) myProcessExecutor.createProcess(viewPath, rootDetectionCommandLine);
      final InputStream response = rootDetectionProcess.executeAndReturnProcessInput(new String[] { "pwv", "-root" });
      final ByteArrayOutputStream content = new ByteArrayOutputStream();
      StreamUtil.copyStreamContent(response, content);
      response.close();
      rootDetectionProcess.shutdown();
      final String viewRoot = content.toString().trim();
      //create persistent process in the view root for caching
      final ClearCaseInteractiveProcess process = (ClearCaseInteractiveProcess) myProcessExecutor.createProcess(viewPath, createCommandLine(viewRoot, "-status"));
      synchronized (ourViewProcesses) {
        ourViewProcesses.put(viewRoot, process);
        LOG.debug(String.format("ClearCaseInteractiveProcess cached for '%s'", viewRoot));
      }
      return process;
    } catch (ExecutionException e) {
      IOException io = new IOException(e.getMessage());
      io.initCause(e);
      throw io;
    }
  }

  @NotNull
  private GeneralCommandLine createCommandLine(final @NotNull String workingDirectory, final String... parameters) {
    final GeneralCommandLine generalCommandLine = new GeneralCommandLine();
    generalCommandLine.setExePath("cleartool");
    generalCommandLine.setWorkDirectory(workingDirectory);
    for (final String parameter : parameters) {
      generalCommandLine.addParameter(parameter);
    }
    return generalCommandLine;
  }

  private InteractiveProcessFacade renewProcess(final @NotNull ClearCaseInteractiveProcess process) throws IOException {
    synchronized (ourViewProcesses) {
      String processKeyToReniew = null;
      for (Map.Entry<String, ClearCaseInteractiveProcess> entry : ourViewProcesses.entrySet()) {
        if (entry.getValue().equals(process)) {
          processKeyToReniew = entry.getKey();
        }
      }
      if (processKeyToReniew != null) {
        InteractiveProcessFacade cached = getProcess(processKeyToReniew);
        cached.destroy();
        ourViewProcesses.remove(processKeyToReniew);
        LOG.debug(String.format("Interactive Process of '%s' has been dropt. Will be recreated.", processKeyToReniew));
      } else {
        LOG.debug(String.format("Could not find process for Renewing."));
      }
      return getProcess(processKeyToReniew);
    }

  }

  protected String getVcsRootProcessKey(final @NotNull VcsRoot root) {
    return root.getProperty(Constants.CC_VIEW_PATH);//TODO: is it right?
  }

  private ClearCaseInteractiveProcess createProcess(String workingDirectory, final Process process) {
    return new ClearCaseInteractiveProcess(workingDirectory, process);
  }

  public void dispose(final @NotNull VcsRoot root) {
    try {
      final String processKey = getVcsRootProcessKey(root);
      if (ourViewProcesses.containsKey(processKey)) {
        synchronized (ourViewProcesses) {
          final ClearCaseInteractiveProcess process = ourViewProcesses.get(processKey);
          if (process != null) {
            process.shutdown();
          }
          ourViewProcesses.remove(processKey);
        }
      }
    } catch (Throwable t) {
      LOG.error(t.getMessage(), t);
    }
  }

  public void dispose() {
    new Thread(new Runnable() {
      public void run() {
        synchronized (ourViewProcesses) {
          for (Map.Entry<String, ClearCaseInteractiveProcess> entry : ourViewProcesses.entrySet()) {
            ClearCaseInteractiveProcess process = entry.getValue();
            destroy(process, 100);
            LOG.debug(String.format("Interactive process for '%s' disposed", entry.getKey()));
          }
          ourViewProcesses.clear();
        }
      }

      private void destroy(final @NotNull ClearCaseInteractiveProcess process, final long timeout) {
        //start new timeout watcher
        new Thread(new Runnable() {
          public void run() {
            try {
              Thread.sleep(timeout);
              if (process.isRunning()) {
                LOG.debug(String.format("Process is still running. Terminating."));//TODO: add working directory to ClearCaseInteractiveProcess                
                process.getProcess().destroy();
              }
            } catch (Throwable t) {
              //noop
            }
          }
        }, String.format("%s: shutdown: wait for process termination", ClearCaseInteractiveProcessPool.class.getSimpleName())).start();
        //attempt to shutdown gracefully
        process.shutdown();
      }
    }, String.format("%s: shutdown", ClearCaseInteractiveProcessPool.class.getSimpleName())).start();

    //waiting for complete subprocess shutdown
    try {
      LOG.debug(String.format("%s: Waiting for the complete shutdown...", ClearCaseInteractiveProcessPool.class.getSimpleName()));
      Thread.sleep(2000);
      LOG.debug(String.format("%s: Shutdown completed.", ClearCaseInteractiveProcessPool.class.getSimpleName()));
    } catch (InterruptedException e) {
      //do nothing
    }
  }

}