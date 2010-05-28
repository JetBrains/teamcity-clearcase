package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;
import java.util.Date;
import java.util.List;

import jetbrains.buildServer.TextLogger;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportContext;
import jetbrains.buildServer.agent.vcs.AgentVcsSupportCore;
import jetbrains.buildServer.agent.vcs.IncludeRuleUpdater;
import jetbrains.buildServer.agent.vcs.UpdateByIncludeRules;
import jetbrains.buildServer.agent.vcs.UpdatePolicy;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;

import org.apache.log4j.Logger;
import org.jetbrains.teamcity.vcs.clearcase.CCDelta;
import org.jetbrains.teamcity.vcs.clearcase.CCException;
import org.jetbrains.teamcity.vcs.clearcase.CCRegion;
import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;
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
    return new CCViewUpdater(root, rule, version, checkoutRoot, logger);
  }

  class CCViewUpdater implements IncludeRuleUpdater {
    
    private VcsRoot myVcsRoot;
    private CheckoutRules myRule;
    private String myVersion;
    private BuildProgressLogger myLogger;
    private File myCheckoutRoot;

    CCViewUpdater(VcsRoot root, CheckoutRules rule, String version, File checkoutRoot, BuildProgressLogger logger){
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
        
        getPublisher().publish(ccview, changes, root, pathWithinView, myLogger);
        
      } catch (Exception e) {
        myLogger.buildFailureDescription("Updating from ClearCase repository failed.");
        throw new VcsException(e);
        
      } finally {
        myLogger.targetFinished("Updating from ClearCase repository...");
      }
      
    }
    
    private Date getDate(String version) {
      return new Date();//TODO: parse version as Date 
    }

    public void dispose() throws VcsException {
      // TODO Auto-generated method stub
    }
    
    private CCSnapshotView getView (CCSnapshotView originView, VcsRoot root, String version, File checkoutRoot, BuildProgressLogger logger) throws CCException {
      //use tmp for build
      checkoutRoot = new File(myAgentConfig.getTempDirectory(), "snapshots");
      checkoutRoot.mkdirs();
      //scan for exists
      final CCSnapshotView existingView = findView(root, originView, checkoutRoot, logger);
      if(existingView != null){
        
        return existingView;
      }
      return createNew(root, originView.getTag(), checkoutRoot, logger);
    }

    /**
     * creates new View's clone in the "checkoutRoot"  
     * @param root 
     * @param sourceViewTag
     * @param checkoutRoot
     * @param logger
     * @return
     */
    private CCSnapshotView createNew(VcsRoot root, String sourceViewTag, File checkoutRoot, BuildProgressLogger logger) throws CCException {
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
    private CCSnapshotView findView(VcsRoot root, CCSnapshotView originView, File checkoutRoot, BuildProgressLogger logger) throws CCException {
      try{
        LOG.debug(String.format("findView::expectedViewName: %s", originView));
        final List<String> originSpecs = originView.getConfigSpec();
        //iterate through root directory and try find required view
        for(File child : checkoutRoot.listFiles()){
          LOG.debug(String.format("findView::child: %s", child));
          if(child.isDirectory() && child.getName().equals(originView.getTag())){//TODO: use agent name? check ConfigSpecs's changed?
            final CCSnapshotView clonedView = new CCSnapshotView(getBuildViewTag(root, originView.getTag()), child.getAbsolutePath());
            final List<String> clonedSpecs = clonedView.getConfigSpec();//test the view is alive also
            LOG.debug(String.format("Found view \"%s\" in %s", clonedView.getTag(), checkoutRoot.getAbsolutePath()));
            //check configspecs are equal and update cloned if it's not so 
            if(!isEquals(originSpecs, clonedSpecs)){
              clonedView.setConfigSpec(originSpecs);
            }
            return clonedView;
          }
        }
        LOG.debug(String.format("findView::found: %s", "no one suitable view found"));
        return null;
        
      } catch (Exception e) {
        throw new CCException(e);
      }
    }

    private boolean isEquals(List<String> originSpecs, List<String> clonedSpecs) {
      return true;
    }

    private String getBuildViewTag(VcsRoot root, String sourceViewTag) throws CCException {
      return String.format("buildagent_%s_vcsroot_%s_%s", myAgentConfig.getName(), root.getId(), sourceViewTag);
    }

    private String getOriginViewTag (VcsRoot root) {
      return root.getProperty(ClearCaseSupport.VIEW_TAG);
    }
    
  }
  
  IChangePublisher getPublisher() {
    return new LinkBasedPublisher();
  }
  

}
