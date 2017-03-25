/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
    LOG.debug(String.format("Checkout directory: '%s'", checkoutRoot.getPath())); //$NON-NLS-1$      
    final File workDirectory = build.getAgentConfiguration().getWorkDirectory();
    LOG.debug(String.format("Work directory: '%s'", workDirectory.getPath())); //$NON-NLS-1$      
    if (!checkoutRoot.equals(new File(workDirectory, pathWithinAView.getPath()))) {
      final String relativePath;
      if (workDirectory.getAbsoluteFile().equals(checkoutRoot.getAbsoluteFile())) {
        relativePath = ".";
      } else {
        relativePath = FileUtil.getRelativePath(workDirectory.getPath(), checkoutRoot.getPath(), File.separatorChar);
      }
      final String errorMessage = String.format("Wrong checkout directory: expected '%s' but found '%s'.\n Set \"VersionControl Settings\\Checkout directory\" equals to \"Relative path within the view\" of \"%s\" Vcs Root", pathWithinAView, relativePath, vcsRoot.getName());
      report(errorMessage, isDisableValidationErrors(build));
    }
    return workDirectory;
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
        build.getBuildLogger().message(String.format("Using ClearCase view '%s' for build", existingOnFileSystemView.getTag()));
        return restore(existingOnFileSystemView);//perhaps view.dat can be corrupted/overrided by other roots/soft...

      } else {
        //have to create new temporary one because CC could not create maps view into existing folder
        build.getBuildLogger().message(String.format("Repairing the View '%s'...", existingOnFileSystemView.getTag()));
        final File tmpViewRoot = new File(build.getAgentConfiguration().getTempDirectory(), String.valueOf(System.currentTimeMillis()));
        LOG.debug(String.format("getView::creating temporary snapshot view in \"%s\"", tmpViewRoot.getAbsolutePath())); //$NON-NLS-1$
        createNew(build, root, tmpViewRoot, logger);
        FileUtil.delete(tmpViewRoot);
        //try lookup again
        return getView(build, root, checkoutRoot, rules, logger);
      }
    }
    build.getBuildLogger().message(String.format("View for '%s' not found, creating new one...", root.getName()));
    final CCSnapshotView newView = createNew(build, root, ccCheckoutRoot, logger);
    build.getBuildLogger().message(String.format("New ClearCase snapshot view '%s' successfully created", newView.getTag()));    
    return newView;
  }

}
