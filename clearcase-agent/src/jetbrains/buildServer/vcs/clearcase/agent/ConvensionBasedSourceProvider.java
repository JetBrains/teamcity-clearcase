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

import java.io.File;

import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.CCDelta;
import jetbrains.buildServer.vcs.clearcase.CCException;
import jetbrains.buildServer.vcs.clearcase.CCSnapshotView;
import jetbrains.buildServer.vcs.clearcase.Constants;

import org.apache.log4j.Logger;

public class ConvensionBasedSourceProvider extends AbstractSourceProvider {

  static final Logger LOG = Logger.getLogger(ConvensionBasedSourceProvider.class);

  public ConvensionBasedSourceProvider() {
  }

  @Override
  protected File getCCRootDirectory(AgentRunningBuild build, File checkoutRoot) {
    return build.getAgentConfiguration().getWorkDirectory();
  }

  public void updateSources(VcsRoot root, CheckoutRules rules, String toVersion, File checkoutDirectory, AgentRunningBuild build, boolean cleanCheckoutRequested) throws VcsException {
    //load server side
    final String serverSidePathWithinAView = root.getProperty(Constants.RELATIVE_PATH);
    //check configuration and warn if differs 
    final String checkoutToRelativePath;
    if (!checkoutDirectory.isAbsolute()) {
      checkoutToRelativePath = FileUtil.normalizeRelativePath(checkoutDirectory.getPath());
    } else {
      checkoutToRelativePath = FileUtil.normalizeRelativePath(FileUtil.getRelativePath(getCCRootDirectory(build, checkoutDirectory), checkoutDirectory));
    }
    if (!new File(serverSidePathWithinAView).equals(new File(checkoutToRelativePath))) {
      LOG.warn(String.format("%s: Wrong checkout directory. Expected \"%s\", but found \"%s\". Perhaps build cannot be succeeded. See more ...", root.getName(), serverSidePathWithinAView, checkoutToRelativePath));
    }
    super.updateSources(root, rules, toVersion, checkoutDirectory, build, cleanCheckoutRequested);
  };

  public void publish(AgentRunningBuild build, CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws CCException {
    //do nothing. all data already should be in the checkout directory
  }

  @Override
  protected CCSnapshotView getView(AgentRunningBuild build, VcsRoot root, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    //use temporary for build
    final File ccCheckoutRoot = getCCRootDirectory(build, checkoutRoot);
    //scan for exists
    final CCSnapshotView existingOnFileSystemView = findView(build, root, ccCheckoutRoot, logger);
    if (existingOnFileSystemView != null) {
      //view's root exist. let's check view exists on server side
      if (isAlive(existingOnFileSystemView)) {
        return restore(existingOnFileSystemView);//perhaps view.dat can be corrupted/overrided by other roots/soft...

      } else {
        //have to create new temporary one because CC could not create maps view into existing folder
        final File tmpViewRoot = new File(build.getAgentConfiguration().getTempDirectory(), String.valueOf(System.currentTimeMillis()));
        LOG.debug(String.format("getView::create temporary snapshot view in \"%s\" but is not alive.", tmpViewRoot.getAbsolutePath()));
        createNew(build, root, tmpViewRoot, logger);
        FileUtil.delete(tmpViewRoot);
        //try lookup again
        return getView(build, root, ccCheckoutRoot, logger);
      }
    }
    return createNew(build, root, ccCheckoutRoot, logger);
  }

  private CCSnapshotView restore(CCSnapshotView existingView) throws CCException {
    return existingView.restore();
  }

  private boolean isAlive(final CCSnapshotView view) throws CCException {
    return view.isAlive();
  }

}
