package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;
import java.util.Date;
import java.util.List;

import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.log4j.Logger;
import org.jetbrains.teamcity.vcs.clearcase.CCDelta;
import org.jetbrains.teamcity.vcs.clearcase.CCException;
import org.jetbrains.teamcity.vcs.clearcase.CCRegion;
import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;
import org.jetbrains.teamcity.vcs.clearcase.Util;


public abstract class AbstractSourceProvider implements ISourceProvider {
  
  static final Logger LOG = Logger.getLogger(AbstractSourceProvider.class);  
  
  protected VcsRoot myVcsRoot;
  protected CheckoutRules myRule;
  protected File myCheckoutRoot;
  protected String myVersion;
  protected BuildProgressLogger myLogger;

  protected BuildAgentConfiguration myAgentConfig;

  AbstractSourceProvider(BuildAgentConfiguration config, VcsRoot root, CheckoutRules rule, String version, File checkoutRoot, BuildProgressLogger logger){
    myAgentConfig = config;
    myVcsRoot = root;
    myRule = rule;
    myCheckoutRoot = checkoutRoot;
    myVersion = version;
    myLogger = logger;
    LOG.debug(String.format("getUpdater::root: %s", root.convertToPresentableString()));
    LOG.debug(String.format("getUpdater::rule: %s", rule.getAsString()));
    LOG.debug(String.format("getUpdater::version: %s", version));
    LOG.debug(String.format("getUpdater::checkoutRoot: %s", checkoutRoot));    
  }

  public void process(IncludeRule includeRule, File root) throws VcsException {
    LOG.debug(String.format("process::rule: %s->%s", includeRule.getFrom(), includeRule.getTo()));
    LOG.debug(String.format("process::root: %s", root));
    
    myLogger.targetStarted("Updating from ClearCase repository...");  
    try {
      //check origin exists
      final String originTag = getOriginViewTag(myVcsRoot);
      final CCSnapshotView originView = Util.Finder.findView(new CCRegion(), originTag);
      if(originView == null){
        throw new CCException(String.format("Could not find \"\" view.", originTag));
      }
      //obtain cloned origin view
      final String pathWithinView = myVcsRoot.getProperty(ClearCaseSupport.RELATIVE_PATH);
      final CCSnapshotView ccview = getView(originView, myVcsRoot, myVersion, myCheckoutRoot, myLogger);
      final CCDelta[] changes = ccview.update(new File(ccview.getLocalPath(), pathWithinView), getDate(myVersion));
      
      publish(ccview, changes, root, pathWithinView, myLogger);
      
    } catch (Exception e) {
      myLogger.buildFailureDescription("Updating from ClearCase repository failed.");
      throw new VcsException(e);
      
    } finally {
      myLogger.targetFinished("Updating from ClearCase repository...");
    }
    
  }
  
  protected abstract File getCCRootDirectory (File checkoutRoot);
  
  protected CCSnapshotView getView (CCSnapshotView originView, VcsRoot root, String version, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    //use tmp for build
    final File ccCheckoutRoot = getCCRootDirectory(checkoutRoot);
    //scan for exists
    final CCSnapshotView existingView = findView(root, originView, ccCheckoutRoot, logger);
    if(existingView != null){
      final List<String> originSpecs = originView.getConfigSpec();
      final List<String> clonedSpecs = existingView.getConfigSpec();//test the view is alive also
      //check configspecs are equal and update cloned if it's not so 
      if(!isEquals(originSpecs, clonedSpecs)){
        LOG.debug("getView:: ConfigSpec will be reseted");
        existingView.setConfigSpec(originSpecs);
      }
      return existingView;
    }
    return createNew(root, originView.getTag(), ccCheckoutRoot, logger);
  }

  /**
   * creates new View's clone in the "checkoutRoot"  
   * @param root 
   * @param sourceViewTag
   * @param checkoutRoot
   * @param logger
   * @return
   */
  protected CCSnapshotView createNew(VcsRoot root, String sourceViewTag, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    final CCRegion region = new CCRegion();
    for(CCSnapshotView view : region.getViews()){
      LOG.debug(String.format("createNew::view: %s", view.getTag()));
      if(sourceViewTag.equals(view.getTag())){
        LOG.debug(String.format("createNew::found tag: %s", view.getTag()));
        final String buildViewTag = getBuildViewTag(root, sourceViewTag);
        //look for existing view with the same tag and drop it if found
        final CCSnapshotView existingWithTheSameTag = Util.Finder.findView(new CCRegion(), buildViewTag);
        if(existingWithTheSameTag != null){
          LOG.debug(String.format("createNew::there already is a view with the same tag: %s. drop it", existingWithTheSameTag));              
          existingWithTheSameTag.drop();
        }
        //create new in the checkout directory
        final CCSnapshotView clone = new CCSnapshotView (buildViewTag, new File(checkoutRoot, sourceViewTag).getAbsolutePath());
        clone.create(String.format("Clone of the \"%s\" view", view.getTag()));
        clone.setConfigSpec(view.getConfigSpec());
        return clone;
      }
    }
    throw new CCException(String.format("Could not find the \"%s\" view", sourceViewTag));
  }

  /**
   * looks for "sourceViewTag" in the "checkoutRoot" directory
   * @param root 
   * @throws CCException 
   */
  protected CCSnapshotView findView(VcsRoot root, CCSnapshotView originView, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    try{
      LOG.debug(String.format("findView::expectedViewName: %s", originView));
      final File candidate = new File(checkoutRoot, originView.getTag());
      if(candidate.exists() && candidate.isDirectory()){
        final CCSnapshotView clonedView = new CCSnapshotView(getBuildViewTag(root, originView.getTag()), candidate.getAbsolutePath());
        LOG.debug(String.format("Found view's folder \"%s\" in %s", originView.getTag(), checkoutRoot.getAbsolutePath()));        
        return clonedView;
      }
      LOG.debug(String.format("findView::found: %s", "no one suitable view's folder found"));
      return null;

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected boolean isEquals(List<String> originSpecs, List<String> clonedSpecs) {
    if(originSpecs != null && clonedSpecs != null){
      final StringBuffer origin = new StringBuffer();
      for(String spec : originSpecs){
        if(spec.trim().length() != 0){
          origin.append(spec.trim());
        }
      }
      final StringBuffer clone = new StringBuffer();
      for(String spec : clonedSpecs){
        if(spec.trim().length() != 0){
          clone.append(spec.trim());
        }
      }
      final String originSpecStr = origin.toString();
      final String originMD5 = DigestUtils.md5Hex(originSpecStr);
      LOG.debug(String.format("isEquals:: origin=%s md5=%s", originSpecStr, originMD5));
      final String cloneSpecStr = clone.toString();
      final String cloneMD5 = DigestUtils.md5Hex(cloneSpecStr);
      LOG.debug(String.format("isEquals:: clone=%s md5=%s", cloneSpecStr, cloneMD5));
      return originMD5.equals(cloneMD5);
    }
    return false;//TODO: compare lines !!!
  }

  protected String getBuildViewTag(VcsRoot root, String sourceViewTag) throws CCException {
    return String.format("buildagent_%s_vcsroot_%s_%s", myAgentConfig.getName(), root.getId(), sourceViewTag);
  }
  
  
  protected String getOriginViewTag (VcsRoot root) {
    return root.getProperty(ClearCaseSupport.VIEW_TAG);
  }
  
  protected Date getDate(String version) {
    return new Date();//TODO: parse version as Date 
  }

  public void dispose() throws VcsException {
    // TODO Auto-generated method stub
  }

  protected String dumpConfig(){
    return String.format("home=%s\nbuildTmp=%s\ntmp=%s\nwork=%s", myAgentConfig.getAgentHomeDirectory(), myAgentConfig.getBuildTempDirectory(), myAgentConfig.getTempDirectory(), myAgentConfig.getWorkDirectory());
  }
  
  

}
