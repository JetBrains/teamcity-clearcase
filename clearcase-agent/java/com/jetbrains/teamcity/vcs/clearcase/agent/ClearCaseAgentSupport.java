package com.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;

import jetbrains.buildServer.TextLogger;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportContext;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportCore;
import jetbrains.buildServer.agent.vcs.UpdateByCheckoutRules;
import jetbrains.buildServer.agent.vcs.UpdatePolicy;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;

public class ClearCaseAgentSupport implements AgentVcsSupportContext, UpdateByCheckoutRules, AgentVcsSupportCore {
  
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
    return true;//check cleartool accesible
  }

  public void updateSources(VcsRoot root, CheckoutRules rule, String version, File checkoutRoot, BuildProgressLogger logger) throws VcsException {
    logger.progressStarted("Updating from ClearCase repository");
    try{
      System.err.println("update...");
      
    } finally{
      logger.progressFinished();
    }
  }

}
