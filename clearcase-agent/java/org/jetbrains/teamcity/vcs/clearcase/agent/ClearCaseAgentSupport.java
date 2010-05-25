package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;
import java.net.InetAddress;
import java.util.Date;

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
import org.jetbrains.teamcity.vcs.clearcase.CCException;
import org.jetbrains.teamcity.vcs.clearcase.CCRegion;
import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;
import org.jetbrains.teamcity.vcs.clearcase.Util;


public class ClearCaseAgentSupport implements AgentVcsSupportContext, UpdateByIncludeRules, AgentVcsSupportCore {
  
  private static final Logger LOG = Logger.getLogger(ClearCaseAgentSupport.class);
  
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
      
      myLogger.progressStarted("Updating from ClearCase repository");  
      try {
        final CCSnapshotView ccview = getView(myVcsRoot, myVersion, myCheckoutRoot, myLogger);
        ccview.update(new File(ccview.getLocalPath(), myVcsRoot.getProperty(ClearCaseSupport.RELATIVE_PATH)), getDate(myVersion));
        
      } catch (CCException e) {
        myLogger.buildFailureDescription("Updating from ClearCase repository failed.");
        throw new VcsException(e);
        
      } finally {
        myLogger.progressFinished();
      }
      
    }
    
    private Date getDate(String version) {
      return new Date();//TODO: parse version as Date 
    }

    public void dispose() throws VcsException {
      // TODO Auto-generated method stub
      
    }
    
    private CCSnapshotView getView (VcsRoot root, String version, File checkoutRoot, BuildProgressLogger logger) throws CCException {
      final String sourceViewTag = getSourceViewTag (root);
      //scan for exists
      final CCSnapshotView existingView = findView(sourceViewTag, checkoutRoot, logger);
      if(existingView != null){
        
        return existingView;
      }
      return createNew(sourceViewTag, checkoutRoot, logger);
    }

    /**
     * creates new View's clone in the "checkoutRoot"  
     * @param sourceViewTag
     * @param checkoutRoot
     * @param logger
     * @return
     */
    private CCSnapshotView createNew(String sourceViewTag, File checkoutRoot, BuildProgressLogger logger) throws CCException {
      logger.targetStarted("Create new Snapshot View for building");
      try{
        final CCRegion region = new CCRegion();
        for(CCSnapshotView view : region.getViews()){
          LOG.debug(String.format("createNew::view: %s", view.getTag()));
          if(sourceViewTag.equals(view.getTag())){
            LOG.debug(String.format("createNew::found tag: %s", view.getTag()));
            final String buildViewTag = getBuildViewTag(sourceViewTag);
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
        
      } finally {
        logger.targetFinished("Create new Snapshot View for building");
      }
    }

    /**
     * looks for "sourceViewTag" in the "checkoutRoot" directory
     * @throws CCException 
     */
    private CCSnapshotView findView(String sourceViewTag, File checkoutRoot, BuildProgressLogger logger) throws CCException {
      logger.targetStarted("Looking for existing view");
      try{
        LOG.debug(String.format("findView::expectedViewName: %s", sourceViewTag));
        for(File child : checkoutRoot.listFiles()){
          LOG.debug(String.format("findView::child: %s", child));
          if(child.isDirectory() && child.getName().equals(sourceViewTag)){//TODO: use agent name? check ConfigSpecs's changed?
            final CCSnapshotView existingView = new CCSnapshotView(getBuildViewTag(sourceViewTag), child.getAbsolutePath());
            existingView.getConfigSpec();//test the view is alive
            LOG.debug(String.format("Found view \"%s\" in %s", existingView.getTag(), checkoutRoot.getAbsolutePath()));
            return existingView;
          }
        }
        LOG.debug(String.format("findView::found: %s", "no one suitable view found"));
        return null;
        
      } catch (Exception e) {
        throw new CCException(e);
        
      } finally {
        logger.targetFinished("Looking for existing view");
      }
    }

    private String getBuildViewTag(String sourceViewTag) throws CCException {
      try{
        return String.format("buildagent_%s_%s", InetAddress.getLocalHost().getHostName(), sourceViewTag);

      } catch (Exception e){
        throw new CCException(e);
      }
    }

    private String getSourceViewTag (VcsRoot root) {
      return root.getProperty(ClearCaseSupport.VIEW_TAG);
    }
    
  }

}
