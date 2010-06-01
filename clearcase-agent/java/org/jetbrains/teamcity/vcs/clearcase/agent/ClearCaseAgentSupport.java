package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;

import jetbrains.buildServer.TextLogger;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportContext;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportCore;
import jetbrains.buildServer.agent.vcs.IncludeRuleUpdater;
import jetbrains.buildServer.agent.vcs.UpdateByIncludeRules;
import jetbrains.buildServer.agent.vcs.UpdatePolicy;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;

import org.apache.log4j.Logger;
import org.jetbrains.teamcity.vcs.clearcase.Util;


public class ClearCaseAgentSupport implements AgentVcsSupportContext, UpdateByIncludeRules, AgentVcsSupportCore {
  
  static final Logger LOG = Logger.getLogger(ClearCaseAgentSupport.class);
  private BuildAgentConfiguration myAgentConfig;
  
  public ClearCaseAgentSupport(final BuildAgentConfiguration config){
    myAgentConfig = config;
  }
  
  public String getName() {
    return "clearcase"; // TODO: move to common constants
  }

  public AgentVcsSupportCore getCore() {
    return this;
  }

  public UpdatePolicy getUpdatePolicy() {
    return this;
  }

  public boolean canRun(BuildAgentConfiguration config, TextLogger logger) {
    try{
      Util.execAndWait("cleartool hostinfo");
      return true;
    } catch (Exception e) {
      logger.info(e.getMessage());
      return false;
    }
  }

  public IncludeRuleUpdater getUpdater(VcsRoot root, CheckoutRules rule, String version, File checkoutRoot, BuildProgressLogger logger) throws VcsException {
    return new ConvensionBasedSourceProvider(myAgentConfig, root, rule, version, checkoutRoot, logger);    
//    return new LinkBasedSourceProvider(myAgentConfig, root, rule, version, checkoutRoot, logger);
  }

}
