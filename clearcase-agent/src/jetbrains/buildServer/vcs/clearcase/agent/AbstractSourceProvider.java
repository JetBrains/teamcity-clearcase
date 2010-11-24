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
import jetbrains.buildServer.vcs.clearcase.Kind;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;

public abstract class AbstractSourceProvider implements ISourceProvider {

  static final Logger LOG = Logger.getLogger(AbstractSourceProvider.class);

  AbstractSourceProvider() {
  }

  public String getSourceViewTag(AgentRunningBuild build, VcsRoot root) throws CCException {
    final Map<String, String> properties = build.getSharedConfigParameters();
    final String key = String.format(Constants.AGENT_SOURCE_VIEW_TAG_PROP_PATTERN, root.getId());
    final String sourceViewTag = properties.get(key);
    if (sourceViewTag == null) {
      LOG.debug(String.format("SharedConfigParameters: %s", properties)); //$NON-NLS-1$
      throw new CCException(String.format(Messages.getString("AbstractSourceProvider.no_tag_in_parameters_error_message"), root.getName())); //$NON-NLS-1$
    }
    LOG.debug(String.format("Found Source View Tag for \"%s\": %s", root.getName(), sourceViewTag)); //$NON-NLS-1$
    return sourceViewTag;
  }

  public String[] getConfigSpecs(AgentRunningBuild build, VcsRoot root) throws CCException {
    // load configSpecs
    final Map<String, String> properties = build.getSharedConfigParameters();
    final String key = String.format(Constants.AGENT_CONFIGSPECS_SYS_PROP_PATTERN, root.getId());
    final String configSpecs = properties.get(key);
    if (configSpecs == null) {
      LOG.debug(String.format("SharedConfigParameters: %s", properties)); //$NON-NLS-1$
      throw new CCException(String.format(Messages.getString("AbstractSourceProvider.no_configspec_in_parameters_error_message"), root.getName())); //$NON-NLS-1$
    } else {
      LOG.debug(String.format("Found ConfigSpecs for \"%s\": %s", root.getName(), configSpecs)); //$NON-NLS-1$
    }
    return configSpecs.split("\n+"); //$NON-NLS-1$
  }

  public void updateSources(VcsRoot root, CheckoutRules rules, String toVersion, File checkoutDirectory, AgentRunningBuild build, boolean cleanCheckoutRequested) throws VcsException {

    build.getBuildLogger().targetStarted(Messages.getString("AbstractSourceProvider.update_root_target_started_message")); //$NON-NLS-1$
    try {
      // obtain cloned origin view
      final String pathWithinView = root.getProperty(Constants.RELATIVE_PATH);
      build.getBuildLogger().message(Messages.getString("AbstractSourceProvider.preparing_view_target_message")); //$NON-NLS-1$
      final CCSnapshotView ccview = getView(build, root, checkoutDirectory, build.getBuildLogger());
      build.getBuildLogger().message(String.format(Messages.getString("AbstractSourceProvider.updating_view_target_message"), toVersion)); //$NON-NLS-1$
      final CCDelta[] changes = setupConfigSpec(ccview, getConfigSpecs(build, root), toVersion);
      final String describe = describe(changes);
      if (describe.trim().length() > 0) {
        build.getBuildLogger().message(String.format(Messages.getString("AbstractSourceProvider.changes_loaded_target_message"), describe)); //$NON-NLS-1$
      } else {
        build.getBuildLogger().message(String.format(Messages.getString("AbstractSourceProvider.no_changes_loaded_target_message"), describe)); //$NON-NLS-1$
      }
      publish(build, ccview, changes, checkoutDirectory, pathWithinView, build.getBuildLogger());

    } catch (Exception e) {
      build.getBuildLogger().buildFailureDescription(Messages.getString("AbstractSourceProvider.update_root_target_error_message")); //$NON-NLS-1$
      throw new VcsException(e);

    } finally {
      build.getBuildLogger().targetFinished(Messages.getString("AbstractSourceProvider.update_root_target_started_message")); //$NON-NLS-1$
    }

  }

  private String describe(CCDelta[] changes) {
    StringBuffer buffer = new StringBuffer();
    int added = 0;
    int changed = 0;
    int removed = 0;
    for (CCDelta change : changes) {
      if (Kind.ADDITION.equals(change.getKind())) {
        added++;
      } else if (Kind.MODIFICATION.equals(change.getKind())) {
        changed++;
      } else if (Kind.DELETION.equals(change.getKind())) {
        removed++;
      } else if (Kind.UNVERSION.equals(change.getKind())) {
        removed++;
      }
    }
    if (added != 0) {
      buffer.append(Messages.getString("AbstractSourceProvider.added_changes_prefix")).append(added).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    if (changed != 0) {
      buffer.append(buffer.length() == 0 ? "" : ", "); //$NON-NLS-1$ //$NON-NLS-2$
      buffer.append(Messages.getString("AbstractSourceProvider.changed_changes_prefix")).append(changed).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    if (removed != 0) {
      buffer.append(buffer.length() == 0 ? "" : ", "); //$NON-NLS-1$ //$NON-NLS-2$
      buffer.append(Messages.getString("AbstractSourceProvider.removed_changes_prefix")).append(removed).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    return buffer.toString().trim();
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
      LOG.debug(String.format("createNew::there already is a view with the same tag: %s. drop it", existingWithTheSameTag)); //$NON-NLS-1$
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
        LOG.debug(String.format("create:: a \"Server Storage Location\" exists for a view")); //$NON-NLS-1$
        break;
      }
    }

    //try to find and use original view location as base 
    LOG.debug(String.format("create:: preparing target location for the snapshot view storage directory")); //$NON-NLS-1$
    final String ccOriginalViewTag = getSourceViewTag(build, root);
    LOG.debug(String.format("create:: found source view tag: \"%s\"", ccOriginalViewTag)); //$NON-NLS-1$
    final CCSnapshotView ccOriginalView = Util.Finder.findView(ccRegion, ccOriginalViewTag);
    if (ccOriginalView == null) {
      throw new CCException(String.format(Messages.getString("AbstractSourceProvider.could_not_find_view_by_tag_error_message"), ccOriginalViewTag)); //$NON-NLS-1$
    }
    //obtain stream if required
    final String ccOriginalStream = ccOriginalView.isUcm() ? ccOriginalView.getStream() : null;

    //check there is any View's location and hope mkview -stgloc -auto will work properly
    final CCSnapshotView clone;
    if (hasViewsStorageLocation) {

      clone = new CCSnapshotView(buildViewTag, ccOriginalStream, null, viewRoot);
    } else {

      //...construct
      final File originalViewGlobalFolder = ccOriginalView.getGlobalPath();
      final File originalViewGlobalFolderLocation = originalViewGlobalFolder.getParentFile();
      LOG.debug(String.format("create:: found Global Location of original view folder: \"%s\"", originalViewGlobalFolderLocation)); //$NON-NLS-1$
      final File buildViewGlobalFolder = new File(originalViewGlobalFolderLocation, String.format("%s.vws", buildViewTag)); //$NON-NLS-1$
      LOG.debug(String.format("create:: use \"%s\" Global Location folder for build view", buildViewGlobalFolder)); //$NON-NLS-1$

      //let's go...
      clone = new CCSnapshotView(buildViewTag, ccOriginalStream, buildViewGlobalFolder, viewRoot);
    }
    clone.create(String.format("Clone view \'%s\' created", buildViewTag)); //$NON-NLS-1$
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
      LOG.debug(String.format("findView::viewRoot=%s", viewRoot.getAbsolutePath())); //$NON-NLS-1$
      if (viewRoot.exists() && viewRoot.isDirectory()) {
        final CCSnapshotView clonedView = new CCSnapshotView(getBuildViewTag(build, root), viewRoot);
        LOG.debug(String.format("Found existing view's root folder \"%s\"", viewRoot.getAbsolutePath())); //$NON-NLS-1$
        return clonedView;
      }
      LOG.debug(String.format("findView::found: %s", "no one suitable view's folder found")); //$NON-NLS-1$ //$NON-NLS-2$
      return null;

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected String getBuildViewTag(AgentRunningBuild build, VcsRoot root) throws CCException {
    return String.format("tcbuildagent_%s_vcsroot_%s", build.getAgentConfiguration().getName(), root.getId()); //$NON-NLS-1$
  }

  protected String getOriginViewTag(VcsRoot root) {
    return new File(root.getProperty(Constants.CC_VIEW_PATH)).getName();
  }

  public void dispose() throws VcsException {
    // TODO Auto-generated method stub
  }

  protected String dumpConfig(AgentRunningBuild build) {
    return String.format("home=%s\nbuildTmp=%s\nwork=%s", build.getAgentConfiguration().getAgentHomeDirectory(), build.getAgentConfiguration().getTempDirectory(), build.getAgentConfiguration().getWorkDirectory()); //$NON-NLS-1$
  }

  protected CCDelta[] setupConfigSpec(CCSnapshotView targetView, String[] sourceSpecs, String toDate) throws CCException {
    final ArrayList<String> timedSpesc = new ArrayList<String>(sourceSpecs.length + 2);
    timedSpesc.add(String.format("time %s", toDate)); //$NON-NLS-1$
    for (String spec : sourceSpecs) {
      timedSpesc.add(spec);
    }
    timedSpesc.add("end time"); //$NON-NLS-1$
    return targetView.setConfigSpec(timedSpesc);
  }

  protected void dumpRules(final CheckoutRules rules) {
    if (rules != null) {
      LOG.debug(String.format("Found CheckoutRiles: {%s}", rules.toString().trim())); //$NON-NLS-1$
    } else {
      LOG.debug("Checkout is null"); //$NON-NLS-1$
    }
  }

}
