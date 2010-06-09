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
import java.util.ArrayList;
import java.util.Map;

import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.CCDelta;
import jetbrains.buildServer.vcs.clearcase.CCException;
import jetbrains.buildServer.vcs.clearcase.CCRegion;
import jetbrains.buildServer.vcs.clearcase.CCSnapshotView;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;


public abstract class AbstractSourceProvider implements ISourceProvider {
  
  static final Logger LOG = Logger.getLogger(AbstractSourceProvider.class);  
  

  AbstractSourceProvider(){
  }
  
  public String[] getConfigSpecs(AgentRunningBuild build, VcsRoot root) throws CCException {
    //load configSpecs
    final Map<String, String> properties = build.getBuildParameters().getSystemProperties();
    final String key = String.format(Constants.CONFIGSPECS_SYS_PROP_PATTERN, root.getId());
    LOG.debug(String.format("Sys props: %s", properties));
    LOG.debug(String.format("Key: %s", key));
    final String configSpecs = properties.get(key);
    if(configSpecs == null){
      throw new CCException(String.format("Could not get ConfigSpecs for \"%s\"", root.getName()));
    } else {
      LOG.debug(String.format("Found ConfigSpecs for \"%s\": %s", root.getName(), configSpecs));
    }
    
    return configSpecs.split("\n+");
  }

  public void updateSources(VcsRoot root, CheckoutRules rules, String toVersion, File destFolder, AgentRunningBuild build, boolean cleanCheckoutRequested) throws VcsException {
    
    build.getBuildLogger().targetStarted("Updating from ClearCase repository...");  
    try {
      //check origin exists
      final String viewRootName = getOriginViewTag(root);
      //obtain cloned origin view
      final String pathWithinView = root.getProperty(Constants.RELATIVE_PATH);
      final CCSnapshotView ccview = getView(build, viewRootName, root, destFolder, build.getBuildLogger());
      final CCDelta[] changes = setupConfigSpec(ccview, getConfigSpecs(build, root), toVersion);
      publish(ccview, changes, destFolder, pathWithinView, build.getBuildLogger());
      
    } catch (Exception e) {
      build.getBuildLogger().buildFailureDescription("Updating from ClearCase repository failed.");
      throw new VcsException(e);
      
    } finally {
      build.getBuildLogger().targetFinished("Updating from ClearCase repository...");
    }
    
  }
  
  protected abstract File getCCRootDirectory (AgentRunningBuild build, File checkoutRoot);
  
  protected CCSnapshotView getView (AgentRunningBuild build, String viewRootName, VcsRoot root, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    //use tmp for build
    final File ccCheckoutRoot = getCCRootDirectory(build, checkoutRoot);
    //scan for exists
    final CCSnapshotView existingView = findView(build, root, viewRootName, ccCheckoutRoot, logger);
    if(existingView != null){
      return existingView;
    }
    return createNew(build, root, viewRootName, ccCheckoutRoot, logger);
  }

  /**
   * creates new View's clone in the "checkoutRoot"  
   * @param root 
   * @param sourceViewTag
   * @param checkoutRoot
   * @param logger
   * @return
   */
  protected CCSnapshotView createNew(AgentRunningBuild build, VcsRoot root, String sourceViewTag, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    final String buildViewTag = getBuildViewTag(build, root, sourceViewTag);
    //look for existing view with the same tag and drop it if found
    final CCSnapshotView existingWithTheSameTag = Util.Finder.findView(new CCRegion(), buildViewTag);
    if(existingWithTheSameTag != null){
      LOG.debug(String.format("createNew::there already is a view with the same tag: %s. drop it", existingWithTheSameTag));              
      existingWithTheSameTag.drop();
    }
    //create new in the checkout directory
    final CCSnapshotView clone = new CCSnapshotView (buildViewTag, new File(checkoutRoot, sourceViewTag).getAbsolutePath());
    clone.create(String.format("Clone of the \"%s\" view", sourceViewTag));
    return clone;
  }

  /**
   * looks for "sourceViewTag" in the "checkoutRoot" directory
   * @param root 
   * @throws CCException 
   */
  protected CCSnapshotView findView(AgentRunningBuild build, VcsRoot root, String viewRootName, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    try{
      LOG.debug(String.format("findView::expectedViewName: %s", viewRootName));
      final File candidate = new File(checkoutRoot, viewRootName);
      if(candidate.exists() && candidate.isDirectory()){
        final CCSnapshotView clonedView = new CCSnapshotView(getBuildViewTag(build, root, viewRootName), candidate.getAbsolutePath());
        LOG.debug(String.format("Found view's folder \"%s\" in %s", viewRootName, checkoutRoot.getAbsolutePath()));        
        return clonedView;
      }
      LOG.debug(String.format("findView::found: %s", "no one suitable view's folder found"));
      return null;

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected String getBuildViewTag(AgentRunningBuild build, VcsRoot root, String sourceViewTag) throws CCException {
    return String.format("buildagent_%s_vcsroot_%s_%s", build.getAgentConfiguration().getName(), root.getId(), sourceViewTag);
  }
  
  
  protected String getOriginViewTag (VcsRoot root) {
    return new File(root.getProperty(Constants.CC_VIEW_PATH)).getName();
  }
  
  public void dispose() throws VcsException {
    // TODO Auto-generated method stub
  }

  protected String dumpConfig(AgentRunningBuild build){
    return String.format("home=%s\nbuildTmp=%s\nwork=%s", 
        build.getAgentConfiguration().getAgentHomeDirectory(), 
        build.getAgentConfiguration().getTempDirectory(), 
        build.getAgentConfiguration().getWorkDirectory());
  }
  
  protected CCDelta[] setupConfigSpec(CCSnapshotView targetView, String[] sourceSpecs, String toDate) throws CCException {
    final ArrayList<String> timedSpesc = new ArrayList<String>(sourceSpecs.length + 2);
    timedSpesc.add(String.format("time %s", toDate));
    for(String spec : sourceSpecs){
      timedSpesc.add(spec);
    }
    timedSpesc.add("end time");    
    return targetView.setConfigSpec(timedSpesc);
  }
  
  

}
