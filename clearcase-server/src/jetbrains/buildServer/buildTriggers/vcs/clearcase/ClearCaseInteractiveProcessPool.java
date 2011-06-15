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

  private static HashMap<Long, ClearCaseInteractiveProcessPool> ourPools = new HashMap<Long, ClearCaseInteractiveProcessPool>();

  private final HashMap<String, ClearCaseInteractiveProcess> myViewProcesses = new HashMap<String, ClearCaseInteractiveProcess>();

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

  private long myId;

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

    private Process myProcess;
    private LinkedList<String> myLastExecutedCommand = new LinkedList<String>();
    private ILineFilter myErrorFilter;
    private String myWorkingDirectory;

    private long myPoolId = -1;
    private String myLastOutput;

    public Process getProcess() {
      return myProcess;
    }

    public String getWorkingDirectory() {
      return myWorkingDirectory;
    }

    public void destroy() {
      //do nothing
    }

    //just for testing purpose 
    protected ClearCaseInteractiveProcess() {
      super(null, null);
    }

    public ClearCaseInteractiveProcess(final long poolId, final String workingDirectory, final Process process) {
      this(workingDirectory, process);
      myPoolId = poolId;
    }

    public ClearCaseInteractiveProcess(final String workingDirectory, final Process process) {
      super(process.getInputStream(), process.getOutputStream());
      myProcess = process;
      myWorkingDirectory = workingDirectory;
    }

    public long getPoolId() {
      return myPoolId;
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

    static Pattern END_OF_COMMAND = Pattern.compile("(.*)Command (\\d*) returned status (\\d*)(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
            throw new IOException(new StringBuilder("Error executing ").append(Arrays.toString(params)).append(": ").append(errorMessage).toString());
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
    protected InputStream getErrorStream() {
      return myProcess.getErrorStream();
    }

    @Override
    protected void forceDestroy() {
      try {
        final int retCode = myProcess.exitValue();
        LOG.debug(String.format("[%d] Destroing Process has already exited with code (%d):", getPoolId(), retCode, myLastExecutedCommand));
      } catch (IllegalThreadStateException e) {
        LOG.debug(String.format("[%d] Destroing Process is still running. Try to waiting for correct termination: %s", getPoolId(), myLastExecutedCommand));
        try {
          myProcess.waitFor();
          forceDestroy();
        } catch (InterruptedException e1) {
          LOG.debug(String.format("[%d] Enforce low-level Process terminating. Last commands: %s", getPoolId(), myLastExecutedCommand));
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
        LOG.debug(String.format("[%d] Interactive Process terminated with code '%d', create new one for the view", getPoolId(), retCode));
        final InteractiveProcessFacade newProcess = getDefault().renewProcess(this, ioe);
        return newProcess.executeAndReturnProcessInput(params);
      } catch (IllegalThreadStateException ite) {
        //process is still running. do not trap IOException
        if (isWaitingForUserInput(ioe)) {
          LOG.debug(String.format("Interrupting process for '%s'", getWorkingDirectory()));
          getProcess().getOutputStream().close();
          getProcess().destroy();
          getDefault().dispose(this);
          LOG.debug(String.format("Process for '%s' interrupted", getWorkingDirectory()));
        }
        throw ioe;
      }
    }

    boolean isWaitingForUserInput(final IOException ioe) {
      if (ioe.getMessage().contains("A snapshot view update is in progress") || ioe.getMessage().contains("An update is already in progress")) {
        LOG.debug(String.format("Waiting for input detected: %s", ioe.getMessage()));
        return true;
      }
      return false;
    }

  }

  private ClearCaseInteractiveProcessPool(long poolId) {
    myId = poolId;
  }

  public long getId() {
    return myId;
  }

  public static ClearCaseInteractiveProcessPool getDefault() {
    synchronized (ourPools) {
      final long poolId = Thread.currentThread().getId();
      ClearCaseInteractiveProcessPool ourThreadDefault = ourPools.get(poolId);
      if (ourThreadDefault == null) {
        ourThreadDefault = new ClearCaseInteractiveProcessPool(poolId);
        ourPools.put(poolId, ourThreadDefault);
        LOG.debug(String.format("[%d] %s created", poolId, ClearCaseInteractiveProcessPool.class.getSimpleName()));
      }
      return ourThreadDefault;
    }
  }

  /**
   * JUST FOR TESTING PURPOSE!
   */
  public void setProcessExecutor(final @NotNull ClearCaseFacade executor) {
    myProcessExecutor = executor;
  }

  public ClearCaseInteractiveProcess getProcess(final @NotNull VcsRoot root) throws IOException {
    synchronized (myViewProcesses) {
      final String processKey = getVcsRootProcessKey(root);
      ClearCaseInteractiveProcess cached = myViewProcesses.get(processKey);
      if (cached == null) {
        //create new
        cached = createProcess(processKey);
        //        cached.addRef();
        myViewProcesses.put(processKey, cached);
      }
      return cached;
    }
  }

  public ClearCaseInteractiveProcess getProcess(final @NotNull String viewPath) throws IOException {
    //check there already is process for the path and reuse them
    synchronized (myViewProcesses) {
      for (final Map.Entry<String, ClearCaseInteractiveProcess> processEntry : myViewProcesses.entrySet()) {
        final String processKey = processEntry.getKey();
        if (Util.isUnderPath(viewPath, processKey)) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("[%d] Cache hit: '%s'->'%s'", getId(), viewPath, processEntry.getKey()));
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
      LOG.debug(String.format("[%d] Creating new ClearCaseInteractiveProcess for '%s'...", getId(), viewPath));
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
      synchronized (myViewProcesses) {
        myViewProcesses.put(viewRoot, process);
        LOG.debug(String.format("[%d] ClearCaseInteractiveProcess cached for '%s'", getId(), viewRoot));
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

  private InteractiveProcessFacade renewProcess(final @NotNull ClearCaseInteractiveProcess process, final @NotNull IOException cause) throws IOException {
    dispose(process);
    LOG.debug(String.format("Existing view processes: %s", myViewProcesses));
    throw cause;
  }

  protected String getVcsRootProcessKey(final @NotNull VcsRoot root) {
    return root.getProperty(Constants.CC_VIEW_PATH);//TODO: is it right?
  }

  private ClearCaseInteractiveProcess createProcess(String workingDirectory, final Process process) {
    return new ClearCaseInteractiveProcess(getId(), workingDirectory, process);
  }

  public void dispose(final @NotNull ClearCaseInteractiveProcess process) throws IOException {
    synchronized (myViewProcesses) {
      String processKeyToReniew = null;
      for (Map.Entry<String, ClearCaseInteractiveProcess> entry : myViewProcesses.entrySet()) {
        if (entry.getValue().equals(process)) {
          processKeyToReniew = entry.getKey();
          break;
        }
      }
      if (processKeyToReniew != null) {
        ClearCaseInteractiveProcess cached = getProcess(processKeyToReniew);
        cached.destroy();
        myViewProcesses.remove(processKeyToReniew);
        LOG.debug(String.format("[%d] Interactive Process of '%s' has been dropt: %s", getId(), cached.getWorkingDirectory(), processKeyToReniew));
      } else {
        LOG.debug(String.format("[%d] Could not find process for Renewing.", getId()));
      }
    }
  }

  public void dispose(final @NotNull VcsRoot root) {
    try {
      final String processKey = getVcsRootProcessKey(root);
      if (myViewProcesses.containsKey(processKey)) {
        synchronized (myViewProcesses) {
          final ClearCaseInteractiveProcess process = myViewProcesses.get(processKey);
          if (process != null) {
            process.shutdown();
          }
          myViewProcesses.remove(processKey);
        }
      }
    } catch (Throwable t) {
      LOG.error(t.getMessage(), t);
    }
  }

  public void dispose() {
    synchronized (ourPools) {
      for (final ClearCaseInteractiveProcess process : myViewProcesses.values()) {
        try {
          process.shutdown();
        } catch (Throwable t) {
          LOG.error(t.getMessage());
        }
      }
      myViewProcesses.clear();
      ourPools.remove(getId());
    }
  }

  public static void disposeAll() {
    synchronized (ourPools) {
      if (ourPools.isEmpty()) {
        LOG.debug(String.format("%s: Nothing to dispose", ClearCaseInteractiveProcessPool.class.getSimpleName()));
        return;
      }
      for (final ClearCaseInteractiveProcessPool pool : ourPools.values()) {
        if (pool.myViewProcesses.isEmpty()) {
          LOG.debug(String.format("%s: Nothing to dispose", ClearCaseInteractiveProcessPool.class.getSimpleName()));
          continue;
        }
        new Thread(new Runnable() {
          public void run() {
            for (Map.Entry<String, ClearCaseInteractiveProcess> entry : pool.myViewProcesses.entrySet()) {
              ClearCaseInteractiveProcess process = entry.getValue();
              destroy(process, 100);
              LOG.debug(String.format("Interactive process for '%s' disposed", entry.getKey()));
            }
            pool.myViewProcesses.clear();
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
  }

}