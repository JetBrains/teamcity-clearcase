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
package jetbrains.buildServer.vcs.clearcase.agent;

import java.io.File;

import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.CCDelta;
import jetbrains.buildServer.vcs.clearcase.CCException;
import jetbrains.buildServer.vcs.clearcase.CCSnapshotView;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public class CheckoutDirectoryBasedSourceProvider extends AbstractSourceProvider {

  static final Logger LOG = Logger.getLogger(CheckoutDirectoryBasedSourceProvider.class);

  public CheckoutDirectoryBasedSourceProvider() {
  }

  @Override
  protected File getCCRootDirectory(AgentRunningBuild build, final @NotNull VcsRoot vcsRoot, final @NotNull File checkoutRoot, final @NotNull CheckoutRules rules) throws VcsValidationException {
    final File pathWithinAView = getRelativePathWithinAView(vcsRoot);
    LOG.debug(String.format("Relative Path within a View: '%s'", pathWithinAView)); //$NON-NLS-1$    
    final File checkoutDirectory = build.getCheckoutDirectory();
    LOG.debug(String.format("Checkout directory: '%s'", checkoutDirectory.getPath())); //$NON-NLS-1$      
    final File workDirectory = build.getAgentConfiguration().getWorkDirectory();//641457143173266e ...
    LOG.debug(String.format("Work directory: '%s'", workDirectory.getPath())); //$NON-NLS-1$      
    if (!checkoutDirectory.equals(new File(workDirectory, pathWithinAView.getPath()))) {
      final String relativePath;
      if (workDirectory.getAbsoluteFile().equals(checkoutDirectory.getAbsoluteFile())) {
        relativePath = ".";
      } else {
        relativePath = FileUtil.getRelativePath(workDirectory.getPath(), checkoutDirectory.getPath(), File.separatorChar);
      }
      final String errorMessage = String.format("Wrong checkout directory: expected '%s' but found '%s'.\n Set \"VersionControl Settings\\Checkout directory\" equals to \"Relative path within the view\" of \"%s\" Vcs Root", pathWithinAView, relativePath, vcsRoot.getName());
      report(errorMessage, isDisableValidationErrors(build));
    }
    return workDirectory;
  }

  //  public void updateSources(VcsRoot root, CheckoutRules rules, String toVersion, File checkoutDirectory, AgentRunningBuild build, boolean cleanCheckoutRequested) throws VcsException {
  //    //check "Checkout Directory" & Checkout Rules matches to the ClearCase View structure
  //    validatePaths(build, checkoutDirectory, root, rules);
  //    //perform update
  //    super.updateSources(root, rules, toVersion, checkoutDirectory, build, cleanCheckoutRequested);
  //  }
  //
  //  void validatePaths(final @NotNull AgentRunningBuild build, final @NotNull File configurationCheckoutDirectory, final @NotNull VcsRoot root, final @NotNull CheckoutRules rules) throws VcsValidationException {
  //    //log configurationCheckoutDirectory
  //    LOG.debug(String.format("build.checkoutDirectory=\"%s\"", build.getCheckoutDirectory())); //$NON-NLS-1$
  //    LOG.debug(String.format("configurationCheckoutDirectory=\"%s\"", configurationCheckoutDirectory)); //$NON-NLS-1$
  //    //make checkout directory relative due to validation issues
  //    final File relativeCheckoutDirectory;
  //    if (configurationCheckoutDirectory.isAbsolute()) {
  //      relativeCheckoutDirectory = new File(FileUtil.getRelativePath(build.getAgentConfiguration().getWorkDirectory(), configurationCheckoutDirectory));
  //      LOG.debug(String.format("make relative configurationCheckoutDirectory=\"%s\"", relativeCheckoutDirectory)); //$NON-NLS-1$      
  //    } else {
  //      relativeCheckoutDirectory = configurationCheckoutDirectory;
  //    }
  //    final File serverSideFullPathWithinTheView = new File(root.getProperty(Constants.CC_VIEW_PATH), root.getProperty(Constants.RELATIVE_PATH));
  //    //log referencing path    
  //    LOG.debug(String.format("serverSideFullPathWithinTheView=\"%s\"", serverSideFullPathWithinTheView)); //$NON-NLS-1$
  //    LOG.debug(String.format("Validating rules {%s}", rules.toString().trim())); //$NON-NLS-1$
  //    if (!CheckoutRules.DEFAULT.equals(rules)) {
  //      if (rules.getIncludeRules().size() == 1) {
  //        final IncludeRule rule = rules.getIncludeRules().get(0);
  //        if (rule.getFrom().trim().length() == 0 || ".".equals(rule.getFrom())) { //$NON-NLS-1$
  //          final File buildCheckoutDirectory = new File(FileUtil.normalizeRelativePath(new File(relativeCheckoutDirectory, rule.getTo()).getPath()));
  //          //log buildCheckoutDirectory
  //          LOG.debug(String.format("validating buildCheckoutDirectory=\"%s\"", buildCheckoutDirectory)); //$NON-NLS-1$
  //          if (isAncestor(buildCheckoutDirectory, serverSideFullPathWithinTheView)) {
  //            //match, log
  //            LOG.debug(String.format("\"%s\" validated, accepted", buildCheckoutDirectory)); //$NON-NLS-1$
  //          } else {
  //            //report: rule doesn't match to expected, error
  //            report(String.format(Messages.getString("ConvensionBasedSourceProvider.unmatched_checkout_root_error_message"), buildCheckoutDirectory, serverSideFullPathWithinTheView), isDisableValidationErrors(build)); //$NON-NLS-1$
  //          }
  //        } else {
  //          //report: from is not ".", error
  //          report(String.format(Messages.getString("ConvensionBasedSourceProvider.unsupported_rule_format_error_message"), rule.getFrom()), isDisableValidationErrors(build)); //$NON-NLS-1$
  //        }
  //      } else {
  //        //report: multiple, error
  //        report(String.format(Messages.getString("ConvensionBasedSourceProvider.multiple_checkout_root_error_message"), rules.toString().trim()), isDisableValidationErrors(build)); //$NON-NLS-1$
  //      }
  //    } else {
  //      //default Checkout Rules
  //      //log validation of configurationCheckoutDirectory, serverSideFullPathWithinTheView
  //      LOG.debug(String.format("validating buildCheckoutDirectory=\"%s\"", relativeCheckoutDirectory)); //$NON-NLS-1$
  //      if (isAncestor(relativeCheckoutDirectory, serverSideFullPathWithinTheView)) {
  //        //match, log
  //        LOG.debug(String.format("\"%s\" validated, accepted", relativeCheckoutDirectory)); //$NON-NLS-1$
  //      } else {
  //        //report: configurationCheckoutDirectory doesn't match to expected, error
  //        report(String.format(Messages.getString("ConvensionBasedSourceProvider.unmatched_checkout_root_error_message"), relativeCheckoutDirectory, serverSideFullPathWithinTheView), isDisableValidationErrors(build)); //$NON-NLS-1$
  //      }
  //    }
  //  }

  public void publish(AgentRunningBuild build, CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws CCException {
    //do nothing. all data already should be in the checkout directory
  }

  @Override
  protected CCSnapshotView getView(AgentRunningBuild build, VcsRoot root, final @NotNull File checkoutRoot, final @NotNull CheckoutRules rules, BuildProgressLogger logger) throws VcsValidationException, CCException {
    //use temporary for build
    final File ccCheckoutRoot = getCCRootDirectory(build, root, checkoutRoot, rules);
    //scan for exists
    final CCSnapshotView existingOnFileSystemView = findView(build, root, ccCheckoutRoot, logger);
    if (existingOnFileSystemView != null) {
      //view's root exist. let's check view exists on server side
      if (isAlive(existingOnFileSystemView)) {
        return restore(existingOnFileSystemView);//perhaps view.dat can be corrupted/overrided by other roots/soft...

      } else {
        //have to create new temporary one because CC could not create maps view into existing folder
        final File tmpViewRoot = new File(build.getAgentConfiguration().getTempDirectory(), String.valueOf(System.currentTimeMillis()));
        LOG.debug(String.format("getView::creating temporary snapshot view in \"%s\"", tmpViewRoot.getAbsolutePath())); //$NON-NLS-1$
        createNew(build, root, tmpViewRoot, logger);
        FileUtil.delete(tmpViewRoot);
        //try lookup again
        return getView(build, root, ccCheckoutRoot, rules, logger);
      }
    }
    return createNew(build, root, ccCheckoutRoot, logger);
  }

}
