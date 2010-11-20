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
import jetbrains.buildServer.vcs.clearcase.CCStorage;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;

public abstract class AbstractSourceProvider implements ISourceProvider {

  static final Logger LOG = Logger.getLogger(AbstractSourceProvider.class);

  AbstractSourceProvider() {
  }

  public String getSourceViewTag(AgentRunningBuild build, VcsRoot root) throws CCException {
    final Map<String, String> properties = build.getBuildParameters().getSystemProperties();
    final String key = String.format(Constants.AGENT_SOURCE_VIEW_TAG_PROP_PATTERN, root.getId());
    final String sourceViewTag = properties.get(key);
    LOG.debug(String.format("Found Source View Tag for \"%s\": %s", root.getName(), sourceViewTag));
    return sourceViewTag;
  }

  public String[] getConfigSpecs(AgentRunningBuild build, VcsRoot root) throws CCException {
    // load configSpecs
    final Map<String, String> properties = build.getBuildParameters().getSystemProperties();
    //    LOG.debug(String.format("getAllParameters().keySet(): %s", toString(properties.keySet())));
    //    LOG.debug(String.format("getAllParameters().values(): %s", toString(properties.values())));    
    final String key = String.format(Constants.AGENT_CONFIGSPECS_SYS_PROP_PATTERN, root.getId());
    final String configSpecs = properties.get(key);
    if (configSpecs == null) {
      throw new CCException(String.format("Could not get ConfigSpecs for \"%s\"", root.getName()));
    } else {
      LOG.debug(String.format("Found ConfigSpecs for \"%s\": %s", root.getName(), configSpecs));
    }

    return configSpecs.split("\n+");
  }

  //  private String toString(Collection<String> values) {
  //    StringBuffer out = new StringBuffer("[");
  //    for(String s : values){
  //      out.append(s).append(",");
  //    }
  //    return out.append("]").toString();
  //  }

  public void updateSources(VcsRoot root, CheckoutRules rules, String toVersion, File checkoutDirectory, AgentRunningBuild build, boolean cleanCheckoutRequested) throws VcsException {

    build.getBuildLogger().targetStarted("Updating from ClearCase repository...");
    try {
      // obtain cloned origin view
      final String pathWithinView = root.getProperty(Constants.RELATIVE_PATH);
      final CCSnapshotView ccview = getView(build, root, checkoutDirectory, build.getBuildLogger());
      final CCDelta[] changes = setupConfigSpec(ccview, getConfigSpecs(build, root), toVersion);
      publish(build, ccview, changes, checkoutDirectory, pathWithinView, build.getBuildLogger());

    } catch (Exception e) {
      build.getBuildLogger().buildFailureDescription("Updating from ClearCase repository failed.");
      throw new VcsException(e);

    } finally {
      build.getBuildLogger().targetFinished("Updating from ClearCase repository...");
    }

  }

  protected abstract File getCCRootDirectory(AgentRunningBuild build, File checkoutRoot);

  protected CCSnapshotView getView(AgentRunningBuild build, VcsRoot root, File checkoutRoot, BuildProgressLogger logger) throws CCException {
    // use tmp for build
    final File ccCheckoutRoot = getCCRootDirectory(build, checkoutRoot);
    // scan for exists
    final CCSnapshotView existingView = findView(build, root, ccCheckoutRoot, logger);
    if (existingView != null) {
      return existingView;
    }
    return createNew(build, root, ccCheckoutRoot, logger);
  }

  /**
   * creates new View's clone in the "checkoutRoot"
   * 
   * @param root
   * @param sourceViewTag
   * @param viewRoot
   * @param logger
   * @return
   */
  protected CCSnapshotView createNew(AgentRunningBuild build, VcsRoot root, File viewRoot, BuildProgressLogger logger) throws CCException {
    final String buildViewTag = getBuildViewTag(build, root);
    // look for existing view with the same tag and drop it if found
    final CCSnapshotView existingWithTheSameTag = Util.Finder.findView(new CCRegion(), buildViewTag);
    if (existingWithTheSameTag != null) {
      LOG.debug(String.format("createNew::there already is a view with the same tag: %s. drop it", existingWithTheSameTag));
      existingWithTheSameTag.drop();
    }
    // create new in the checkout directory
    return create(build, root, buildViewTag, viewRoot);
  }

  private CCSnapshotView create(AgentRunningBuild build, final VcsRoot root, final String buildViewTag, final File viewRoot) throws CCException {
    final CCRegion ccRegion = new CCRegion();

    //check there id already is any Server Storage Location for a view
    boolean hasViewsStorageLocation = false;

    for (CCStorage storage : ccRegion.getStorages()) {
      if (CCStorage.View.equals(storage.getType())) {
        hasViewsStorageLocation = true;
        LOG.debug(String.format("create:: a \"Server Storage Location\" exists for a view"));
        break;
      }
    }

    //check there is any View's location and hope mkview -stgloc -auto will work properly
    final CCSnapshotView clone;
    if (hasViewsStorageLocation) {
      clone = new CCSnapshotView(buildViewTag, viewRoot);
    } else {

      //try to find and use original view location as base 
      LOG.debug(String.format("create:: preparing target location for the snapshot view storage directory"));
      final String ccOriginalViewTag = getSourceViewTag(build, root);
      LOG.debug(String.format("create:: found source view tag: \"%s\"", ccOriginalViewTag));
      final CCSnapshotView ccOriginalView = Util.Finder.findView(ccRegion, ccOriginalViewTag);
      if (ccOriginalView == null) {
        throw new CCException(String.format("Could not find view for tag \"%s\"", ccOriginalViewTag));
      }

      //...construct
      final File originalViewGlobalFolder = ccOriginalView.getGlobalPath();
      final File originalViewGlobalFolderLocation = originalViewGlobalFolder.getParentFile();
      LOG.debug(String.format("create:: found Global Location of original view folder: \"%s\"", originalViewGlobalFolderLocation));
      final File buildViewGlobalFolder = new File(originalViewGlobalFolderLocation, String.format("%s.vws", buildViewTag));
      LOG.debug(String.format("create:: use \"%s\" Global Location folder for build view", buildViewGlobalFolder));

      //let's go...
      clone = new CCSnapshotView(buildViewTag, buildViewGlobalFolder, viewRoot);
    }
    clone.create(String.format("Clone view \'%s\' created", buildViewTag));
    return clone;
  }

  /**
   * looks for "sourceViewTag" in the "checkoutRoot" directory
   * 
   * @param root
   * @throws CCException
   */
  protected CCSnapshotView findView(AgentRunningBuild build, VcsRoot root, File viewRoot, BuildProgressLogger logger) throws CCException {
    try {
      LOG.debug(String.format("findView::viewRoot=%s", viewRoot.getAbsolutePath()));
      if (viewRoot.exists() && viewRoot.isDirectory()) {
        final CCSnapshotView clonedView = new CCSnapshotView(getBuildViewTag(build, root), viewRoot);
        LOG.debug(String.format("Found existing view's root folder \"%s\"", viewRoot.getAbsolutePath()));
        return clonedView;
      }
      LOG.debug(String.format("findView::found: %s", "no one suitable view's folder found"));
      return null;

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected String getBuildViewTag(AgentRunningBuild build, VcsRoot root) throws CCException {
    return String.format("buildagent_%s_vcsroot_%s", build.getAgentConfiguration().getName(), root.getId());
  }

  protected String getOriginViewTag(VcsRoot root) {
    return new File(root.getProperty(Constants.CC_VIEW_PATH)).getName();
  }

  public void dispose() throws VcsException {
    // TODO Auto-generated method stub
  }

  protected String dumpConfig(AgentRunningBuild build) {
    return String.format("home=%s\nbuildTmp=%s\nwork=%s", build.getAgentConfiguration().getAgentHomeDirectory(), build.getAgentConfiguration().getTempDirectory(), build.getAgentConfiguration().getWorkDirectory());
  }

  protected CCDelta[] setupConfigSpec(CCSnapshotView targetView, String[] sourceSpecs, String toDate) throws CCException {
    final ArrayList<String> timedSpesc = new ArrayList<String>(sourceSpecs.length + 2);
    timedSpesc.add(String.format("time %s", toDate));
    for (String spec : sourceSpecs) {
      timedSpesc.add(spec);
    }
    timedSpesc.add("end time");
    return targetView.setConfigSpec(timedSpesc);
  }

  protected void dumpRules(final CheckoutRules rules) {
    if (rules != null) {
      LOG.debug(String.format("Found CheckoutRiles: {%s}", rules.toString().trim()));
    } else {
      LOG.debug("Checkout is null");
    }
  }

}
