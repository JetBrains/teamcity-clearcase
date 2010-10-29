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

import jetbrains.buildServer.TextLogger;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.vcs.AgentVcsSupport;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportCore;
import jetbrains.buildServer.agent.vcs.UpdatePolicy;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;

public class ClearCaseAgentSupport extends AgentVcsSupport {
  
  static final Logger LOG = Logger.getLogger(ClearCaseAgentSupport.class);
  
  public ClearCaseAgentSupport(){
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
    try{
      Util.execAndWait(getCheckExecutionCommand());
      return true;
    } catch (Exception e) {
      LOG.info(String.format("Failed to use ClearCase checkout on the agent. See details below."));
      LOG.info(String.format("User: %s", System.getProperty("user.name")));
      LOG.info(String.format("Path: %s", System.getenv("PATH")));
      LOG.info(String.format("Error message: %s", e.getMessage()));
      LOG.debug(e);      
      return false;
    }
  }
  
  protected String getCheckExecutionCommand() {
    return "cleartool hostinfo"; 
  }

}
