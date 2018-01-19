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
package jetbrains.buildServer.vcs.clearcase.agent;

import java.io.File;
import java.text.ParseException;
import java.util.regex.Pattern;
import jetbrains.buildServer.TextLogger;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.vcs.*;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.DateRevision;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.Revision;
import jetbrains.buildServer.util.Dates;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.CTool;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ClearCaseAgentSupport extends AgentVcsSupport {

  static final Logger LOG = Logger.getLogger(ClearCaseAgentSupport.class);

  private Boolean canRun;

  public ClearCaseAgentSupport() {
  }

  @NotNull
  @Override
  public String getName() {
    return Constants.NAME;
  }

  @NotNull
  @Override
  public AgentVcsSupportCore getCore() {
    return this;
  }

  @NotNull
  @Override
  public UpdatePolicy getUpdatePolicy() {
    return new SourceProviderFactory();//new RuleBasedSourceProvider();///* new LinkBasedSourceProvider() */new ConvensionBasedSourceProvider();
  }

  @Override
  public boolean canRun(@NotNull BuildAgentConfiguration config, @NotNull TextLogger logger) {
    if (canRun == null) {
      canRun = canRun(config);
    }
    return canRun;
  }

  @NotNull
  @Override
  public AgentCheckoutAbility canCheckout(@NotNull final VcsRoot vcsRoot, @NotNull final CheckoutRules checkoutRules, @NotNull final AgentRunningBuild build) {
    return new SourceProviderFactory().canCheckout(vcsRoot, checkoutRules, build);
  }

  private boolean canRun(BuildAgentConfiguration config) {
    setupCleartool(config);
    //check can run
    dumpEnvironment();    
    try {
      Util.execAndWait(getCheckExecutionCommand());
      return true;
    } catch (Exception e) {
      if (isCleartoolNotFound(e)) {
        LOG.info(String.format("ClearCase agent checkout is disabled: \"cleartool\" is not in PATH and \"%s\" property is not defined.", CTool.CLEARTOOL_EXEC_PATH_PROP));
      } else {
        LOG.info(String.format("ClearCase agent checkout is disabled: %s", e.getMessage()));
      }
      LOG.debug(String.format("%s: %s", CTool.CLEARTOOL_EXEC_PATH_PROP, config.getBuildParameters().getSystemProperties().get(CTool.CLEARTOOL_EXEC_PATH_PROP)));
      LOG.debug(String.format("%s: %s", CTool.CLEARTOOL_EXEC_PATH_ENV, config.getBuildParameters().getEnvironmentVariables().get(CTool.CLEARTOOL_EXEC_PATH_ENV)));
      LOG.debug(e.getMessage(), e);
      return false;
    }

  }

  private void dumpEnvironment() {
    LOG.debug(String.format("ClearCase Agent Support environment:"));    
    LOG.debug(String.format("Running user: %s", System.getProperty("user.name")));
    LOG.debug(String.format("Environment variables: %s", System.getenv()));
  }

  private void setupCleartool(BuildAgentConfiguration config) {
    //check property(environment variable) is set and set executable path to CTool if exists
    String cleartoolExecPath = config.getBuildParameters().getSystemProperties().get(CTool.CLEARTOOL_EXEC_PATH_PROP);
    if (cleartoolExecPath != null) {
      CTool.setCleartoolExecutable(cleartoolExecPath);
    } else {
      cleartoolExecPath = config.getBuildParameters().getEnvironmentVariables().get(CTool.CLEARTOOL_EXEC_PATH_ENV);
      if (cleartoolExecPath != null) {
        CTool.setCleartoolExecutable(cleartoolExecPath);
      }
    }
  }

  boolean isCleartoolNotFound(Exception e) {
    return Pattern.matches(String.format(".*CreateProcess: %s error=2", getCheckExecutionCommand()), e.getMessage().trim());
  }

  protected String getCheckExecutionCommand() {
    return String.format("%s hostinfo", CTool.getCleartoolExecutable());
  }

  static class SourceProviderFactory implements UpdateByCheckoutRules2 {

    public void updateSources(@NotNull final VcsRoot root,
                              @NotNull final CheckoutRules rules,
                              @NotNull final String toVersion,
                              @NotNull final File checkoutDirectory,
                              @NotNull final AgentRunningBuild build,
                              final boolean cleanCheckoutRequested) throws VcsException {
      final String preparedToVersion = prepareVersion(toVersion);
      if (preparedToVersion == null) return;
      final ISourceProvider delegate = new RuleBasedSourceProvider();
      delegate.validate(build.getCheckoutDirectory(), root, rules);
      LOG.debug(String.format("Passed parameters accepted by '%s' for checkout", delegate.getClass().getSimpleName()));
      LOG.debug(String.format("use '%s' for checkout", delegate.getClass().getSimpleName()));
      delegate.updateSources(root, rules, preparedToVersion, checkoutDirectory, build, cleanCheckoutRequested);
    }

    @NotNull
    public AgentCheckoutAbility canCheckout(final VcsRoot root, final CheckoutRules rules, final AgentRunningBuild build) {
      try {
        new RuleBasedSourceProvider().validate(build.getCheckoutDirectory(), root, rules);
      } catch (VcsValidationException e) {
        return AgentCheckoutAbility.notSupportedCheckoutRules(e.getMessage());
      }
      return AgentCheckoutAbility.canCheckout();
    }

    @Nullable
    private static String prepareVersion(@NotNull final String toVersion) throws VcsException {
      try {
        final DateRevision revision = Revision.fromNotNullString(toVersion).getDateRevision();
        if (revision == null) return null;
        return Revision.fromDate(Dates.after(revision.getDate(), Dates.ONE_SECOND)).getDateString(); // add one second to not miss the change with exactly the same date
      }
      catch (final ParseException e) {
        throw new VcsException(e);
      }
    }
  }
}
