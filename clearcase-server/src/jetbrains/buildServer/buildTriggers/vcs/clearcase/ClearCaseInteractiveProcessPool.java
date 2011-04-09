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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

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

public class ClearCaseInteractiveProcessPool {

  private static Logger LOG = Logger.getLogger(ClearCaseConnection.class);

  private static ClearCaseInteractiveProcessPool ourDefault;

  private final HashMap<String, ClearCaseInteractiveProcess> ourViewProcesses = new HashMap<String, ClearCaseInteractiveProcess>();

  private ClearCaseFacade myProcessExecutor = new ClearCaseFacade() {
    public ClearCaseInteractiveProcess createProcess(final GeneralCommandLine generalCommandLine) throws ExecutionException {
      return ClearCaseInteractiveProcessPool.this.createProcess(generalCommandLine.createProcess());
    }
  };

  public static class ClearCaseInteractiveProcess extends InteractiveProcess {
    private final Process myProcess;
    private LinkedList<String> myLastExecutedCommand = new LinkedList<String>();

    public Process getProcess() {
      return myProcess;
    }

    public void destroy() {
      //do nothing
    }

    public ClearCaseInteractiveProcess(final Process process) {
      super(process.getInputStream(), process.getOutputStream());
      myProcess = process;
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
            String error = readError();
            throw new IOException(new StringBuilder("Error executing ").append(Arrays.toString(params)).append(": ").append(error).toString());
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

    private void release() {
      super.destroy();
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
          if (ClearCaseConnection.LOG.isDebugEnabled()) {
            ClearCaseConnection.LOG.debug(String.format("Cache hit: '%s'->'%s'", viewPath, processEntry.getKey()));
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
    if (ClearCaseConnection.LOG.isDebugEnabled()) {
      ClearCaseConnection.LOG.debug(String.format("Create new ClearCaseInteractiveProcess for '%s'", viewPath));
    }
    final GeneralCommandLine generalCommandLine = new GeneralCommandLine();
    generalCommandLine.setExePath("cleartool");
    generalCommandLine.addParameter("-status");
    generalCommandLine.setWorkDirectory(viewPath);
    try {
      return (ClearCaseInteractiveProcess) myProcessExecutor.createProcess(generalCommandLine);
    } catch (ExecutionException e) {
      IOException io = new IOException(e.getMessage());
      io.initCause(e);
      throw io;
    }
  }

  public InteractiveProcessFacade renewProcess(final @NotNull VcsRoot root) throws IOException {
    synchronized (ourViewProcesses) {
      InteractiveProcessFacade cached = getProcess(root);
      if (cached != null) {
        cached.destroy();
        ourViewProcesses.remove(getVcsRootProcessKey(root));
        ClearCaseConnection.LOG.debug(String.format("Interactive Process of '%s' has been dropt. Recreated."));
      }
    }
    return getProcess(root);
  }

  protected String getVcsRootProcessKey(final @NotNull VcsRoot root) {
    return root.getProperty(Constants.CC_VIEW_PATH);//TODO: is it right?
  }

  private ClearCaseInteractiveProcess createProcess(final Process process) {
    return new ClearCaseInteractiveProcess(process);
  }

  void dispose() {
    synchronized (ourViewProcesses) {
      for (Map.Entry<String, ClearCaseInteractiveProcess> entry : ourViewProcesses.entrySet()) {
        entry.getValue().release();
        LOG.debug(String.format("Interactive process for '%s' disposed", entry.getKey()));
      }
      ourViewProcesses.clear();
    }
  }
  
}