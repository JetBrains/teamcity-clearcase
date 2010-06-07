package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;
import java.io.IOException;

import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.Constants;

import org.apache.log4j.Logger;
import org.jetbrains.teamcity.vcs.clearcase.CCDelta;
import org.jetbrains.teamcity.vcs.clearcase.CCException;
import org.jetbrains.teamcity.vcs.clearcase.CCRegion;
import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;
import org.jetbrains.teamcity.vcs.clearcase.Util;

import ch.fhnw.filecopier.CopyJob;
import ch.fhnw.filecopier.FileCopier;
import ch.fhnw.filecopier.Source;

public class ConvensionBasedSourceProvider extends AbstractSourceProvider {

  static final Logger LOG = Logger.getLogger(LinkBasedSourceProvider.class);
  
  ConvensionBasedSourceProvider(BuildAgentConfiguration config, VcsRoot root, CheckoutRules rule, String version, File checkoutRoot, BuildProgressLogger logger) {
    super(config, root, rule, version, checkoutRoot, logger);
  }

  @Override
  protected File getCCRootDirectory(File checkoutRoot) {
    return myAgentConfig.getWorkDirectory();
  }

  public void publish(CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException, InterruptedException {
    //do nothing. all data already should be in the checkout directory
  }
  
  @Override
  protected CCSnapshotView getView (CCSnapshotView originView, VcsRoot root, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    //use temporary for build
    final File ccCheckoutRoot = getCCRootDirectory(checkoutRoot);
    //scan for exists
    final CCSnapshotView existingView = findView(root, originView, ccCheckoutRoot, logger);
    if(existingView != null){
      if(existingView.isAlive()){
        return existingView;
        
      } else{
        //it means the local folder is exists but created outside CC support - by build system(perhaps already filled with other VCS roots data)
        //let's create new temporary view and then copy system info
        LOG.debug(String.format("getView::view's structure exists in \"%s\" but is not alive.", existingView.getLocalPath()));
        final CCSnapshotView emptyView = createEmptyView(originView, root, logger);
        //move all from the empty view to checkout root(it means all CC system data will be moved to appropriate place and expected view becomes alive)
        move(emptyView.getLocalPath().getAbsoluteFile(), ccCheckoutRoot.getAbsoluteFile());
        //try lookup again
        return getView(originView, root, ccCheckoutRoot, logger);
      }
    }
    return createNew(root, originView.getTag(), ccCheckoutRoot, logger);
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
  protected CCSnapshotView createEmptyView(CCSnapshotView originView, VcsRoot root, BuildProgressLogger logger) throws CCException {
    final File tempCCRoot = new File(myAgentConfig.getTempDirectory(), String.format("clearcase-agent-%s.tmp", System.currentTimeMillis()));
    tempCCRoot.mkdirs();
    final String buildViewTag = getBuildViewTag(root, originView.getTag());
    //look for existing view with the same tag and drop it if found
    final CCSnapshotView existingWithTheSameTag = Util.Finder.findView(new CCRegion(), buildViewTag);
    if(existingWithTheSameTag != null){
      LOG.debug(String.format("createEmptyView::there already is a view with the same tag: %s. drop it", existingWithTheSameTag));              
      existingWithTheSameTag.drop();
    }
    //create new empty in the checkout directory
    final CCSnapshotView temporaryCCView = new CCSnapshotView (buildViewTag, new File(tempCCRoot, originView.getTag()).getAbsolutePath());
    temporaryCCView.create(String.format("Clone of the \"%s\" view", originView.getTag()));
    LOG.debug(String.format("createEmptyView::Empty view \"%s\" created", temporaryCCView.getTag()));
    return temporaryCCView;
  }
  

  @Override
  public void process(IncludeRule includeRule, File root) throws VcsException {
    // have to check the checkout directory fit to CC view structure
    final int ccRootPrefixLength = myAgentConfig.getWorkDirectory().getAbsolutePath().length();
    final String configRelativePath = FileUtil.normalizeRelativePath(root.getAbsolutePath().substring(ccRootPrefixLength, root.getAbsolutePath().length()));
    final String vcsRootRelativePath = FileUtil.normalizeRelativePath(new File(getOriginViewTag(myVcsRoot), myVcsRoot.getProperty(Constants.RELATIVE_PATH)).getPath());
    LOG.debug(String.format("configRelativePath=\"%s\"", configRelativePath));
    LOG.debug(String.format("vcsRootRelativePath=\"%s\"", vcsRootRelativePath));
    if (!new File(configRelativePath).equals(new File(vcsRootRelativePath))) {
      throw new VcsException(String.format("Could not update sources of \"%s\" on the Agent. You have to specify checkout directory as \"%s\"", myVcsRoot.getName(), vcsRootRelativePath.replace("/", File.separator)));
    }
    super.process(includeRule, root);
  }
  
}
