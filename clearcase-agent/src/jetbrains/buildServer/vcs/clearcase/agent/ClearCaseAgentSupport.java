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
package jetbrains.buildServer.vcs.clearcase.agent;

import java.util.regex.Pattern;

import jetbrains.buildServer.TextLogger;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.vcs.AgentVcsSupport;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportCore;
import jetbrains.buildServer.agent.vcs.UpdatePolicy;
import jetbrains.buildServer.vcs.clearcase.CTool;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;

public class ClearCaseAgentSupport extends AgentVcsSupport {

  static final Logger LOG = Logger.getLogger(ClearCaseAgentSupport.class);
  
  private Boolean canRun;

  public ClearCaseAgentSupport() {
  }

  public String getName() {
    return Constants.NAME;
  }

  public AgentVcsSupportCore getCore() {
    return this;
  }

  public UpdatePolicy getUpdatePolicy() {
    return /* new LinkBasedSourceProvider() */new ConvensionBasedSourceProvider();
  }

  public boolean canRun(BuildAgentConfiguration config, TextLogger logger) {
    if(canRun == null){
      canRun = canRun(config);
    }
    return canRun;
  }
  
  private boolean canRun(BuildAgentConfiguration config){
    //check variable is set and set executable path to CTool if exists 
    final String cleartoolExecPath = config.getBuildParameters().getEnvironmentVariables().get(CTool.CLEARTOOL_EXEC_PATH_ENV);
    if(cleartoolExecPath != null){
      CTool.setCleartoolExecutable(cleartoolExecPath);
    }
    //check can run
    try {
      Util.execAndWait(getCheckExecutionCommand());
      return true;
    } catch (Exception e) {
      if (isCleartoolNotFound(e)) {
        LOG.info(String.format("ClearCase agent checkout is disabled: \"cleartool\" is not in PATH and \"%s\" environment variable is not defined.", CTool.CLEARTOOL_EXEC_PATH_ENV));        
      } else {
        LOG.info(String.format("ClearCase agent checkout is disabled for some reasons. Turn on DEBUG logging level for more detail."));
      }
      LOG.debug(String.format("User: %s", System.getProperty("user.name")));
      LOG.debug(String.format("Path: %s", System.getenv("PATH")));
      LOG.debug(String.format("Error message: %s", e.getMessage()));
      LOG.debug(e);
      return false;
    }
    
  }

  boolean isCleartoolNotFound(Exception e) {
    return Pattern.matches(String.format(".*CreateProcess: %s error=2", getCheckExecutionCommand()), e.getMessage().trim());
  }

  protected String getCheckExecutionCommand() {
    return String.format("%s hostinfo", CTool.getCleartoolExecutable());
  }

}
