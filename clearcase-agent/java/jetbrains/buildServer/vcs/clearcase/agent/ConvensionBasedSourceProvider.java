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
import jetbrains.buildServer.vcs.clearcase.CCRegion;
import jetbrains.buildServer.vcs.clearcase.CCSnapshotView;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;
import jetbrains.buildServer.vcs.clearcase.Util.FileSystem.CopyJob;
import jetbrains.buildServer.vcs.clearcase.Util.FileSystem.FileCopier;
import jetbrains.buildServer.vcs.clearcase.Util.FileSystem.Source;

import org.apache.log4j.Logger;

public class ConvensionBasedSourceProvider extends AbstractSourceProvider {

  static final Logger LOG = Logger.getLogger(ConvensionBasedSourceProvider.class);
  
  ConvensionBasedSourceProvider() {
  }
  
  @Override
  protected File getCCRootDirectory(AgentRunningBuild build, File checkoutRoot) {
    return build.getAgentConfiguration().getWorkDirectory();
  }
  
  public void updateSources(VcsRoot root, CheckoutRules rules, String toVersion, File destFolder, AgentRunningBuild build, boolean cleanCheckoutRequested) throws VcsException {
    //load server side
    File serverSideViewRoot = new File(root.getProperty(Constants.CC_VIEW_PATH));
    
    // have to check the checkout directory fit to CC view structure
    final int ccRootPrefixLength = build.getAgentConfiguration().getWorkDirectory().getAbsolutePath().length();
    final String configRelativePath = FileUtil.normalizeRelativePath(destFolder.getAbsolutePath().substring(ccRootPrefixLength, destFolder.getAbsolutePath().length()));
    final String vcsRootRelativePath = FileUtil.normalizeRelativePath(new File(serverSideViewRoot.getName(), root.getProperty(Constants.RELATIVE_PATH)).getPath());
    LOG.debug(String.format("configRelativePath=\"%s\"", configRelativePath));
    LOG.debug(String.format("vcsRootRelativePath=\"%s\"", vcsRootRelativePath));
    if (!new File(configRelativePath).equals(new File(vcsRootRelativePath))) {
      //TODO: review message
      throw new VcsException(String.format("Could not update sources of \"%s\" on the Agent. You have to specify checkout directory as \"%s\"", root.getName(), vcsRootRelativePath.replace("/", File.separator)));
    }
    super.updateSources(root, rules, toVersion, destFolder, build, cleanCheckoutRequested);
    
  };

  public void publish(CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws CCException {
    //do nothing. all data already should be in the checkout directory
  }
  
  @Override
  protected CCSnapshotView getView (AgentRunningBuild build, String viewRootName, VcsRoot root, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    //use temporary for build
    final File ccCheckoutRoot = getCCRootDirectory(build, checkoutRoot);
    //scan for exists
    final CCSnapshotView existingView = findView(build, root, viewRootName, ccCheckoutRoot, logger);
    if(existingView != null){
      if(existingView.isAlive()){
        return existingView;
        
      } else{
        //it means the local folder is exists but created outside CC support - by build system(perhaps already filled with other VCS roots data)
        //let's create new temporary view and then copy system info
        LOG.debug(String.format("getView::view's structure exists in \"%s\" but is not alive.", existingView.getLocalPath()));
        final CCSnapshotView emptyView = createEmptyView(build, viewRootName, root, logger);
        //move all from the empty view to checkout root(it means all CC system data will be moved to appropriate place and expected view becomes alive)
        move(emptyView.getLocalPath().getAbsoluteFile(), ccCheckoutRoot.getAbsoluteFile());
        //try lookup again
        return getView(build, viewRootName, root, ccCheckoutRoot, logger);
      }
    }
    return createNew(build, root, viewRootName, ccCheckoutRoot, logger);
  }
  
  private void move (File from, File to) throws CCException {
    try {
      final CopyJob copy = new CopyJob(new Source[] {new Source(from.getAbsolutePath())}, new String[] {to.getAbsolutePath()});
      new FileCopier().copy(new CopyJob[] {copy});
      FileUtil.delete(from);
    } catch (Exception e){
      throw new CCException(e);
    }
  }
  
  /**
   * creates empty view (without loading rules)
   */
  protected CCSnapshotView createEmptyView(AgentRunningBuild build, String viewRootName, VcsRoot root, BuildProgressLogger logger) throws CCException {
    final File tempCCRoot = new File(build.getAgentConfiguration().getTempDirectory(), String.format("clearcase-agent-%s.tmp", System.currentTimeMillis()));
    tempCCRoot.mkdirs();
    final String buildViewTag = getBuildViewTag(build, root, viewRootName);
    //look for existing view with the same tag and drop it if found
    final CCSnapshotView existingWithTheSameTag = Util.Finder.findView(new CCRegion(), buildViewTag);
    if(existingWithTheSameTag != null){
      LOG.debug(String.format("createEmptyView::there already is a view with the same tag: %s. drop it", existingWithTheSameTag));              
      existingWithTheSameTag.drop();
    }
    //create new empty in the checkout directory
    final CCSnapshotView temporaryCCView = new CCSnapshotView (buildViewTag, new File(tempCCRoot, viewRootName).getAbsolutePath());
    temporaryCCView.create(String.format("Clone of the \"%s\" view", viewRootName));
    LOG.debug(String.format("createEmptyView::Empty view \"%s\" created", temporaryCCView.getTag()));
    return temporaryCCView;
  }
  
}
