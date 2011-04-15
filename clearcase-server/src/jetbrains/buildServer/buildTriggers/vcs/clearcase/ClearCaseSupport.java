/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jetbrains.buildServer.Used;
import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseInteractiveProcessPool.ClearCaseInteractiveProcess;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseValidation.IValidation;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseValidation.ValidationComposite;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpec;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpecLoadRule;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpecParseUtil;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.structure.ClearCaseStructureCache;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.BuildStartContext;
import jetbrains.buildServer.serverSide.BuildStartContextProcessor;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.MultiMap;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.filters.Filter;
import jetbrains.buildServer.util.filters.FilterUtil;
import jetbrains.buildServer.vcs.BuildPatchByIncludeRules;
import jetbrains.buildServer.vcs.BuildPatchPolicy;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.CollectChangesByIncludeRules;
import jetbrains.buildServer.vcs.CollectChangesPolicy;
import jetbrains.buildServer.vcs.FileRule;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.IncludeRuleChangeCollector;
import jetbrains.buildServer.vcs.IncludeRulePatchBuilder;
import jetbrains.buildServer.vcs.LabelingSupport;
import jetbrains.buildServer.vcs.ModificationData;
import jetbrains.buildServer.vcs.ServerVcsSupport;
import jetbrains.buildServer.vcs.TestConnectionSupport;
import jetbrains.buildServer.vcs.VcsChange;
import jetbrains.buildServer.vcs.VcsChangeInfo;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsFileContentProvider;
import jetbrains.buildServer.vcs.VcsModification;
import jetbrains.buildServer.vcs.VcsPersonalSupport;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.VcsRootEntry;
import jetbrains.buildServer.vcs.VcsSupportCore;
import jetbrains.buildServer.vcs.VcsSupportUtil;
import jetbrains.buildServer.vcs.clearcase.CCException;
import jetbrains.buildServer.vcs.clearcase.CCSnapshotView;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;
import jetbrains.buildServer.vcs.patches.PatchBuilder;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.util.io.FileUtil;

public class ClearCaseSupport extends ServerVcsSupport implements VcsPersonalSupport, LabelingSupport, VcsFileContentProvider, CollectChangesByIncludeRules, BuildPatchByIncludeRules, TestConnectionSupport, BuildStartContextProcessor {

  private static final Logger LOG = Logger.getLogger(ClearCaseSupport.class);

  private static final boolean USE_CC_CACHE = !TeamCityProperties.getBoolean("clearcase.disable.caches");

  private @Nullable
  ClearCaseStructureCache myCache;

  private Pattern[] myIgnoreErrorPatterns;

  private static ClearCaseSupport ourDefault;

  public static ClearCaseSupport getDefault() {
    return ourDefault;
  }

  public ClearCaseSupport() {
    ourDefault = this;
    myCache = null;
  }

  public ClearCaseSupport(File baseDir) {
    this();
    if (baseDir != null) {
      myCache = new ClearCaseStructureCache(baseDir, this);
    }
  }

  public ClearCaseSupport(final @NotNull SBuildServer server, final @NotNull ServerPaths serverPaths, final @NotNull EventDispatcher<BuildServerListener> dispatcher) {
    this();
    File cachesRootDir = new File(new File(serverPaths.getCachesDir()), "clearCase");
    if (!cachesRootDir.exists() && !cachesRootDir.mkdirs()) {
      myCache = null;
      return;
    }
    myCache = new ClearCaseStructureCache(cachesRootDir, this);
    if (USE_CC_CACHE) {
      myCache.register(server, dispatcher);
    }

    server.registerExtension(BuildStartContextProcessor.class, this.getClass().getName(), this);

    //listen server shutdown for cleaning purpose
    dispatcher.addListener(new BuildServerAdapter() {
      @Override
      public void serverShutdown() {
        LOG.debug(String.format("Invoke ClearCaseInteractiveProcessPool shutdown..."));
        try {
          ClearCaseInteractiveProcessPool.getDefault().dispose();
        } catch (Throwable t) {
          LOG.error(t.getMessage(), t);
        }
      }
    });

  }

  @NotNull
  public static ViewPath getViewPath(@NotNull final VcsRoot vcsRoot) throws VcsException {
    final String viewPath = vcsRoot.getProperty(Constants.VIEW_PATH);
    if (viewPath != null && trim(viewPath).length() != 0) {
      return getViewPath(viewPath);
    }
    return new ViewPath(vcsRoot.getProperty(Constants.CC_VIEW_PATH), vcsRoot.getProperty(Constants.RELATIVE_PATH));
  }

  @NotNull
  public static ViewPath getRootPath(@NotNull final VcsRoot vcsRoot) throws VcsException {
    final ViewPath viewPath = getViewPath(vcsRoot);
    final String vobRelativePath;
    final String relativePath = viewPath.getRelativePathWithinTheView();
    int pos = relativePath.indexOf(File.separatorChar);
    if (pos < 0) {
      pos = relativePath.length();
    } else {
      if (relativePath.substring(0, pos).equals(Constants.VOBS_NAME_ONLY)) {
        pos = relativePath.indexOf(File.separatorChar, pos + 1);
        if (pos < 0)
          pos = relativePath.length();
      }
    }
    vobRelativePath = relativePath.substring(0, pos);
    return new ViewPath(viewPath.getClearCaseViewPath(), vobRelativePath);
  }

  @NotNull
  public static ViewPath getViewPath(@NotNull final String viewPath) throws VcsException {
    final String ccViewRoot;
    try {
      ccViewRoot = ClearCaseConnection.getClearCaseViewRoot(viewPath);
    } catch (IOException e) {
      throw new VcsException(e);
    }
    return new ViewPath(ccViewRoot, getRelativePath(new File(ccViewRoot), new File(viewPath)));
  }

  @Nullable
  private static String getRelativePath(@NotNull final File parent, @NotNull final File subFile) throws VcsException {
    final StringBuilder sb = new StringBuilder("");
    File file = subFile;

    boolean first = true;

    while (file != null && !CCPathElement.areFilesEqual(file, parent)) {
      if (!first) {
        sb.insert(0, File.separatorChar);
      } else {
        first = false;
      }
      sb.insert(0, file.getName());
      file = file.getParentFile();
    }

    if (file == null)
      return null;

    return sb.toString();
  }

  public ClearCaseConnection createConnection(final VcsRoot root, final FileRule includeRule, @Nullable final ConfigSpecLoadRule loadRule) throws VcsException {
    return createConnection/*doCreateConnection*/(root, includeRule, false, loadRule);
  }

  public ClearCaseConnection createConnection(final VcsRoot root, final FileRule includeRule, final boolean checkCSChange, @Nullable final ConfigSpecLoadRule loadRule) throws VcsException {
    final ViewPath viewPath = getViewPath(root);
    if (includeRule.getFrom().length() > 0) {
      viewPath.setIncludeRuleFrom(includeRule);
    }
    return doCreateConnectionWithViewPath(root, checkCSChange, viewPath);
  }

  private ClearCaseConnection doCreateConnectionWithViewPath(final VcsRoot root, final boolean checkCSChange, final ViewPath viewPath) throws VcsException {
    try {
      return new ClearCaseConnection(viewPath, /*isUCM, */myCache, root, checkCSChange);
    } catch (Exception e) {
      if (e instanceof VcsException) {
        throw (VcsException) e;
      } else {
        throw new VcsException(e);
      }
    }
  }

  private ChangedFilesProcessor createCollectingChangesFileProcessor(final MultiMap<CCModificationKey, VcsChange> key2changes, final Set<String> addFileActivities, final Set<VcsChange> zeroToOneChangedFiles, final ClearCaseConnection connection) {
    return new ChangedFilesProcessor() {

      public void processChangedDirectory(@NotNull final HistoryElement element) throws IOException, VcsException {
        LOG.debug("Processing changed directory " + element.getLogRepresentation());
        CCParseUtil.processChangedDirectory(element, connection, createChangedStructureProcessor(element, key2changes, addFileActivities, connection));
      }

      public void processDestroyedFileVersion(@NotNull final HistoryElement element) {
      }

      public void processChangedFile(@NotNull final HistoryElement element) throws VcsException, IOException {
        if (element.getObjectVersionInt() > getMaxVersionToIgnore(element)) {
          String pathWithoutVersion = connection.getParentRelativePathWithVersions(element.getObjectName(), true);

          final String versionAfterChange = pathWithoutVersion + CCParseUtil.CC_VERSION_SEPARATOR + element.getObjectVersion();
          final String versionBeforeChange = pathWithoutVersion + CCParseUtil.CC_VERSION_SEPARATOR + element.getPreviousVersion(connection, false);

          final VcsChange change = addChange(element, element.getObjectName(), connection, VcsChangeInfo.Type.CHANGED, versionBeforeChange, versionAfterChange, key2changes);

          if (element.getObjectVersionInt() == 1) {
            zeroToOneChangedFiles.add(change);
          }

          LOG.debug("Change was detected: changed file " + element.getLogRepresentation());
        }
      }

    };
  }

  private int getMaxVersionToIgnore(final HistoryElement element) {
    return Constants.MAIN.equals(element.getObjectLastBranch()) ? 1 : 0;
  }

  private ChangedStructureProcessor createChangedStructureProcessor(final HistoryElement element, final MultiMap<CCModificationKey, VcsChange> key2changes, final Set<String> addFileActivities, final ClearCaseConnection connection) {
    return new ChangedStructureProcessor() {
      public void fileAdded(@NotNull final SimpleDirectoryChildElement simpleChild) throws VcsException, IOException {
        final DirectoryChildElement child = simpleChild.createFullElement(connection);
        if (child != null && connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), true)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.ADDED, null, getVersion(child, connection), key2changes);
          addFileActivities.add(element.getActivity());
          LOG.debug("Change was detected: added file \"" + child.getFullPath() + "\"");
        }
      }

      public void fileDeleted(@NotNull final SimpleDirectoryChildElement simpleChild) throws VcsException, IOException {
        final DirectoryChildElement child = simpleChild.createFullElement(connection);
        if (child != null && connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), true)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.REMOVED, getVersion(child, connection), null, key2changes);
          LOG.debug("Change was detected: deleted file \"" + child.getFullPath() + "\"");
        }
      }

      public void directoryDeleted(@NotNull final SimpleDirectoryChildElement simpleChild) throws VcsException, IOException {
        final DirectoryChildElement child = simpleChild.createFullElement(connection);
        if (child != null && connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), false)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.DIRECTORY_REMOVED, getVersion(child, connection), null, key2changes);
          LOG.debug("Change was detected: deleted directory \"" + child.getFullPath() + "\"");
        }
      }

      public void directoryAdded(@NotNull final SimpleDirectoryChildElement simpleChild) throws VcsException, IOException {
        final DirectoryChildElement child = simpleChild.createFullElement(connection);
        if (child != null && connection.versionIsInsideView(child.getPathWithoutVersion(), child.getStringVersion(), false)) {
          addChange(element, child.getFullPath(), connection, VcsChangeInfo.Type.DIRECTORY_ADDED, null, getVersion(child, connection), key2changes);
          LOG.debug("Change was detected: added directory \"" + child.getFullPath() + "\"");
        }
      }
    };
  }

  private String getVersion(final DirectoryChildElement child, final ClearCaseConnection connection) throws VcsException {
    return connection.getObjectRelativePathWithVersions(child.getFullPath(), DirectoryChildElement.Type.FILE.equals(child.getType()));
  }

  private VcsChange addChange(final HistoryElement element, final String childFullPath, final ClearCaseConnection connection, final VcsChangeInfo.Type type, final String beforeVersion, final String afterVersion, final MultiMap<CCModificationKey, VcsChange> key2changes) throws VcsException {
    final CCModificationKey modificationKey = new CCModificationKey(element.getDateString(), element.getUser(), element.getActivity());
    final VcsChange change = createChange(type, connection, beforeVersion, afterVersion, childFullPath);
    key2changes.putValue(modificationKey, change);
    CCModificationKey realKey = findKey(modificationKey, key2changes);
    if (realKey != null) {
      realKey.getCommentHolder().update(element.getActivity(), element.getComment(), connection.getVersionDescription(childFullPath, !isFile(type)));
    }
    return change;
  }

  @Nullable
  private CCModificationKey findKey(final CCModificationKey modificationKey, final MultiMap<CCModificationKey, VcsChange> key2changes) {
    for (CCModificationKey key : key2changes.keySet()) {
      if (key.equals(modificationKey))
        return key;
    }
    return null;
  }

  private VcsChange createChange(final VcsChangeInfo.Type type, ClearCaseConnection connection, final String beforeVersion, final String afterVersion, final String childFullPath) throws VcsException {
    String relativePath = connection.getObjectRelativePathWithoutVersions(childFullPath, isFile(type));
    return new VcsChange(type, relativePath, relativePath, beforeVersion, afterVersion);
  }

  private boolean isFile(final VcsChangeInfo.Type type) {
    switch (type) {
    case ADDED:
    case CHANGED:
    case REMOVED: {
      return true;
    }

    default: {
      return false;
    }
    }
  }

  public void buildPatch(VcsRoot root, String fromVersion, String toVersion, PatchBuilder builder, final IncludeRule includeRule) throws IOException, VcsException {
    buildPatchForConnection(builder, fromVersion, toVersion, createConnection(root, includeRule, true, null));
  }

  private void buildPatchForConnection(PatchBuilder builder, String fromVersion, String toVersion, ClearCaseConnection connection) throws IOException, VcsException {
    try {
      final boolean useCache = USE_CC_CACHE && !connection.getConfigSpec().hasLabelBasedVersionSelector();
      new CCPatchProvider(connection, useCache).buildPatch(builder, fromVersion, toVersion);
    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (ParseException e) {
      throw new VcsException(e);
    } finally {
      connection.dispose();
    }
  }

  @NotNull
  public byte[] getContent(@NotNull final VcsModification vcsModification, @NotNull final VcsChangeInfo change, @NotNull final VcsChangeInfo.ContentType contentType, @NotNull final VcsRoot vcsRoot) throws VcsException {
    final ClearCaseConnection connection = createConnection(vcsRoot, IncludeRule.createDefaultInstance(), null);
    final String filePath = new File(connection.getViewWholePath()).getParent() + File.separator + (contentType == VcsChangeInfo.ContentType.BEFORE_CHANGE ? change.getBeforeChangeRevisionNumber() : change.getAfterChangeRevisionNumber());

    return getFileContent(connection, filePath);

  }

  private byte[] getFileContent(final ClearCaseConnection connection, final String filePath) throws VcsException {
    try {
      final File tempFile = FileUtil.createTempFile("cc", "tmp");
      FileUtil.delete(tempFile);

      try {

        connection.loadFileContent(tempFile, filePath);
        if (tempFile.isFile()) {
          return FileUtil.loadFileBytes(tempFile);
        } else {
          throw new VcsException("Cannot get content of " + filePath);
        }
      } finally {
        FileUtil.delete(tempFile);
      }
    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (InterruptedException e) {
      throw new VcsException(e);
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  public byte[] getContent(@NotNull final String filePath, @NotNull final VcsRoot versionedRoot, @NotNull final String version) throws VcsException {
    final String preparedPath = CCPathElement.normalizeSeparators(filePath);
    final ClearCaseConnection connection = createConnection(versionedRoot, IncludeRule.createDefaultInstance(), null);
    try {
      connection.collectChangesToIgnore(version);
      String path = new File(connection.getViewWholePath()).getParent() + File.separator + connection.getObjectRelativePathWithVersions(connection.getViewWholePath() + File.separator + preparedPath, true);
      return getFileContent(connection, path);
    } finally {
      try {
        connection.dispose();
      } catch (IOException e) {
        //ignore
      }
    }
  }

  @NotNull
  public String getName() {
    return Constants.NAME;
  }

  @NotNull
  @Used("jsp")
  public String getDisplayName() {
    return "ClearCase";
  }

  @NotNull
  public PropertiesProcessor getVcsPropertiesProcessor() {
    return new AbstractVcsPropertiesProcessor() {

      public Collection<InvalidProperty> process(Map<String, String> properties) {
        final ArrayList<InvalidProperty> validationResult = new ArrayList<InvalidProperty>();
        //collect all validation errors 
        final ValidationComposite composite = new ValidationComposite(new IValidation[] { new ClearCaseValidation.CleartoolValidator(), new ClearCaseValidation.ClearcaseViewRootPathValidator(), new ClearCaseValidation.ClearcaseViewRelativePathValidator(),
            new ClearCaseValidation.ClearcaseGlobalLabelingValidator() });
        //transform to expected 
        final Map<IValidation, Collection<InvalidProperty>> result = composite.validate(properties);
        for (final Map.Entry<IValidation, Collection<InvalidProperty>> entry : result.entrySet()) {
          validationResult.addAll(entry.getValue());
        }
        return validationResult;
      }
    };
  }

  @NotNull
  public String getVcsSettingsJspFilePath() {
    return "clearcaseSettings.jsp";
  }

  @NotNull
  public String getCurrentVersion(@NotNull final VcsRoot root) throws VcsException {
    return CCParseUtil.formatDate(new Date());
  }

  @Override
  public boolean isCurrentVersionExpensive() {
    return false;
  }

  @NotNull
  public String getVersionDisplayName(@NotNull final String version, @NotNull final VcsRoot root) throws VcsException {
    return version;
  }

  @NotNull
  public Comparator<String> getVersionComparator() {
    return new VcsSupportUtil.DateVersionComparator(CCParseUtil.getDateFormat());
  }

  @Override
  public boolean isAgentSideCheckoutAvailable() {
    return true;
  }

  @NotNull
  public String describeVcsRoot(VcsRoot vcsRoot) {
    try {
      return "clearcase: " + getViewPath(vcsRoot).getWholePath();
    } catch (VcsException e) {
      return Constants.NAME;
    }
  }

  public String testConnection(final @NotNull VcsRoot vcsRoot) throws VcsException {
    final String[] validationResult = new String[] { "Passed" };
    //validate in general
    final ValidationComposite composite = new ValidationComposite(new IValidation[] { new ClearCaseValidation.ClearcaseViewRootPathValidator(), new ClearCaseValidation.ClearcaseViewRelativePathValidator(), new ClearCaseValidation.CleartoolValidator(),
        new ClearCaseValidation.ClearcaseConfigurationValidator(), new ClearCaseValidation.ClearcaseViewValidator(), new ClearCaseValidation.IValidation() {
          public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
            try {
              ClearCaseConnection caseConnection = createConnection(vcsRoot, IncludeRule.createDefaultInstance(), null);
              try {
                validationResult[0] = caseConnection.testConnection();

              } finally {
                caseConnection.dispose();
              }
            } catch (Exception e) {
              validationResultBuffer.add(
              //it fired by "Relative path..." setting because others already checked hard I guess... 
                  new InvalidProperty(Constants.RELATIVE_PATH, String.format(Messages.getString("ClearCaseSupport.clearcase_view_relative_path_is_not_under_configspec_loading_rules"), e.getMessage()))); //$NON-NLS-1$
              LOG.info(e.getMessage());
              LOG.debug(e);
              return false;
            }
            return true;
          }

          public String getDescription() {
            return "Summary functionality check";
          }
        } });
    //fire validation
    final Map<IValidation, Collection<InvalidProperty>> validationErrors = composite.validate(vcsRoot.getProperties());
    //format exception if something is
    final StringBuffer readableOut = new StringBuffer();
    if (!validationErrors.isEmpty()) {
      for (final Map.Entry<IValidation, Collection<InvalidProperty>> entry : validationErrors.entrySet()) {
        for (final InvalidProperty prop : entry.getValue()) {
          readableOut.append(String.format("%s\n", prop.getInvalidReason()));
        }
      }
      throw new VcsException(readableOut.toString());
    }
    //all ok
    return validationResult[0];
  }

  @Nullable
  public Map<String, String> getDefaultVcsProperties() {
    return new HashMap<String, String>();
  }

  @NotNull
  public Collection<String> mapFullPath(@NotNull final VcsRootEntry rootEntry, @NotNull final String fullPath) {
    final ViewPath viewPath;

    try {
      viewPath = getViewPath(rootEntry.getVcsRoot());
    } catch (VcsException e) {
      LOG.debug("CC.MapFullPath: View path not defined: " + e.getLocalizedMessage());
      return Collections.emptySet();
    }

    final String serverViewRelativePath = cutOffVobsDir(viewPath.getRelativePathWithinTheView().replace("\\", "/"));

    final String normFullPath = cutOffVobsDir(fullPath.replace("\\", "/"));

    if (isAncestor(serverViewRelativePath, normFullPath)) {
      String result = normFullPath.substring(serverViewRelativePath.length());
      if (result.startsWith("/") || result.startsWith("\\")) {
        result = result.substring(1);
      }

      LOG.debug("CC.MapFullPath: File " + normFullPath + " is under " + serverViewRelativePath + " result is " + result);
      return Collections.singleton(result);
    } else {
      LOG.debug("CC.MapFullPath: File " + normFullPath + " is not under " + serverViewRelativePath);
      return Collections.emptySet();
    }
  }

  private String cutOffVobsDir(String serverViewRelativePath) {
    if (StringUtil.startsWithIgnoreCase(serverViewRelativePath, Constants.VOBS)) {
      serverViewRelativePath = serverViewRelativePath.substring(Constants.VOBS.length());
    }
    return serverViewRelativePath;
  }

  private boolean isAncestor(final String sRelPath, final String relPath) {
    return sRelPath.equalsIgnoreCase(relPath) || StringUtil.startsWithIgnoreCase(relPath, sRelPath + "/");
  }

  @Override
  @NotNull
  public VcsSupportCore getCore() {
    return this;
  }

  @Override
  public VcsPersonalSupport getPersonalSupport() {
    return this;
  }

  @Override
  public LabelingSupport getLabelingSupport() {
    return this;
  }

  @NotNull
  public VcsFileContentProvider getContentProvider() {
    return this;
  }

  @NotNull
  public CollectChangesPolicy getCollectChangesPolicy() {
    return this;
  }

  @NotNull
  public BuildPatchPolicy getBuildPatchPolicy() {
    return this;
  }

  public List<ModificationData> collectChanges(final VcsRoot root, final String fromVersion, final String currentVersion, final IncludeRule includeRule) throws VcsException {
    LOG.debug(String.format("Attempt connect to '%s'", root.convertToPresentableString()));
    try {
      final ClearCaseConnection connection = createConnection(root, includeRule, null);
      return collectChangesWithConnection(root, fromVersion, currentVersion, connection);
    } catch (VcsException e) {
      LOG.debug(String.format("Could not establish connection: %s", e.getMessage()));
      throw e;
    }
  }

  private List<ModificationData> collectChangesWithConnection(VcsRoot root, String fromVersion, String currentVersion, ClearCaseConnection connection) throws VcsException {
    try {
      final ArrayList<ModificationData> list = new ArrayList<ModificationData>();
      final MultiMap<CCModificationKey, VcsChange> key2changes = new MultiMap<CCModificationKey, VcsChange>();
      final Set<String> addFileActivities = new HashSet<String>();
      final Set<VcsChange> zeroToOneChangedFiles = new HashSet<VcsChange>();

      final ChangedFilesProcessor fileProcessor = createCollectingChangesFileProcessor(key2changes, addFileActivities, zeroToOneChangedFiles, connection);

      try {
        LOG.debug("Collecting changes...");
        CCParseUtil.processChangedFiles(connection, fromVersion, currentVersion, fileProcessor);

        for (CCModificationKey key : key2changes.keySet()) {
          final List<VcsChange> changes = key2changes.get(key);
          if (addFileActivities.contains(key.getActivity())) {
            FilterUtil.filterCollection(changes, new Filter<VcsChange>() {
              public boolean accept(@NotNull final VcsChange data) {
                return !zeroToOneChangedFiles.contains(data);
              }
            });
          }

          if (changes.isEmpty())
            continue;

          final Date date = new SimpleDateFormat(CCParseUtil.OUTPUT_DATE_FORMAT).parse(key.getDate());
          final String version = CCParseUtil.formatDate(new Date(date.getTime() + 1000));
          list.add(new ModificationData(date, changes, key.getCommentHolder().toString(), key.getUser(), root, version, version));
        }

      } catch (Exception e) {
        throw new VcsException(e);
      }

      Collections.sort(list, new Comparator<ModificationData>() {
        public int compare(final ModificationData o1, final ModificationData o2) {
          return o1.getVcsDate().compareTo(o2.getVcsDate());
        }
      });

      return list;
    } finally {
      LOG.debug("Collecting changes was finished.");

      try {
        connection.dispose();
      } catch (IOException e) {
        //ignore
      }
    }
  }

  public String label(@NotNull final String label, @NotNull final String version, @NotNull final VcsRoot root, @NotNull final CheckoutRules checkoutRules) throws VcsException {
    createLabel(label, root);

    final VersionProcessor labeler = getClearCaseLabeler(label);
    final ConnectionProcessor childrenProcessor = getChildrenProcessor(version, labeler);

    for (IncludeRule includeRule : checkoutRules.getRootIncludeRules()) {
      doWithConnection(root, includeRule, childrenProcessor);
      doWithRootConnection(root, getParentsProcessor(version, labeler, createPath(root, includeRule)));
    }

    return label;
  }

  private String createPath(@NotNull final VcsRoot root, @NotNull final IncludeRule includeRule) throws VcsException {
    final ViewPath viewPath = getViewPath(root);
    viewPath.setIncludeRuleFrom(includeRule);
    return viewPath.getWholePath();
  }

  private ConnectionProcessor getParentsProcessor(final String version, final VersionProcessor labeler, final String path) {
    return new ConnectionProcessor() {
      public void process(@NotNull final ClearCaseConnection connection) throws VcsException {
        connection.processAllParents(version, labeler, path);
      }
    };
  }

  private ConnectionProcessor getChildrenProcessor(final String version, final VersionProcessor labeler) {
    return new ConnectionProcessor() {
      public void process(@NotNull final ClearCaseConnection connection) throws VcsException {
        connection.processAllVersions(version, labeler, true, true);
      }
    };
  }

  private void doWithConnection(@NotNull final VcsRoot root, @NotNull final IncludeRule includeRule, @NotNull final ConnectionProcessor processor) throws VcsException {
    final ClearCaseConnection connection = createConnection(root, includeRule, null);
    processConnection(processor, connection);
  }

  private void doWithRootConnection(@NotNull final VcsRoot root, @NotNull final ConnectionProcessor processor) throws VcsException {
    final ClearCaseConnection connection = doCreateConnectionWithViewPath(root, false, getRootPath(root));//createRootConnection(root);
    processConnection(processor, connection);
  }

  private void processConnection(final ConnectionProcessor processor, final ClearCaseConnection connection) throws VcsException {
    try {
      processor.process(connection);
    } finally {
      try {
        connection.dispose();
      } catch (IOException e) {
        //ignore
      }
    }
  }

  private VersionProcessor getClearCaseLabeler(@NotNull final String label) {
    return new VersionProcessor() {
      public void processFile(final String fileFullPath, final String relPath, final String pname, final String version, final ClearCaseConnection clearCaseConnection, final boolean text, final boolean executable) throws VcsException {
        try {
          clearCaseConnection.mklabel(version, fileFullPath, label, false);
        } catch (IOException e) {
          throw new VcsException(e);
        }
      }

      public void processDirectory(final String fileFullPath, final String relPath, final String pname, final String version, final ClearCaseConnection clearCaseConnection) throws VcsException {
        try {
          clearCaseConnection.mklabel(version, fileFullPath, label, true);
        } catch (IOException e) {
          throw new VcsException(e);
        }
      }

      public void finishProcessingDirectory() {

      }
    };
  }

  private void createLabel(final String label, final VcsRoot root) throws VcsException {
    final boolean useGlobalLabel = "true".equals(root.getProperty(Constants.USE_GLOBAL_LABEL));
    try {
      final List<String> parameters = new ArrayList<String>();
      parameters.add("mklbtype");
      if (useGlobalLabel) {
        parameters.add("-global");
      }
      if (ClearCaseConnection.isLabelExists(getViewPath(root).getWholePath(), label)) {
        parameters.add("-replace");
      }
      parameters.add("-c");
      parameters.add("Label created by TeamCity");
      if (useGlobalLabel) {
        final String globalLabelsVob = root.getProperty(Constants.GLOBAL_LABELS_VOB);
        parameters.add(label + "@" + globalLabelsVob);
      } else {
        parameters.add(label);
      }
      final ClearCaseInteractiveProcess process = ClearCaseInteractiveProcessPool.getDefault().getProcess(root);
      final InputStream input = process.executeAndReturnProcessInput(makeArray(parameters));
      try {
        input.close();
        process.destroy();
      } catch (IOException e) {
        //ignore
      }
    } catch (/*Vcs*//*IO*/Exception e) {
      if (!e.getLocalizedMessage().contains("already exists")) {
        final VcsException ioe = new VcsException(e);
        throw ioe;//e;
      }
    }
  }

  @NotNull
  public static String[] makeArray(@NotNull final List<String> parameters) {
    final String[] array = new String[parameters.size()];
    for (int i = 0; i < parameters.size(); i++) {
      array[i] = parameters.get(i);
    }
    return array;
  }

  public boolean sourcesUpdatePossibleIfChangesNotFound(@NotNull final VcsRoot root) {
    try {
      final ViewPath path = getViewPath(root);
      final ConfigSpec spec = ConfigSpecParseUtil.getConfigSpec(path);
      if (spec != null && spec.hasLabelBasedVersionSelector()) {
        // as far we still cannot detect label moving,
        // have to use all resources set for the patch creation
        return true;
      }
    } catch (VcsException e) {
      LOG.error(e.getMessage(), e);

    } catch (IOException e) {
      LOG.error(e.getMessage(), e);
    }
    return false;
  }

  @NotNull
  public IncludeRuleChangeCollector getChangeCollector(@NotNull final VcsRoot root, @NotNull final String fromVersion, @Nullable final String currentVersion) throws VcsException {
    return new IncludeRuleChangeCollector() {
      @NotNull
      public List<ModificationData> collectChanges(@NotNull final IncludeRule includeRule) throws VcsException {
        return ClearCaseSupport.this.collectChanges(root, fromVersion, currentVersion, includeRule);
      }

      public void dispose() {
        //nothing to do
      }
    };
  }

  @NotNull
  public IncludeRulePatchBuilder getPatchBuilder(@NotNull final VcsRoot root, @Nullable final String fromVersion, @NotNull final String toVersion) {
    return new IncludeRulePatchBuilder() {
      public void buildPatch(@NotNull final PatchBuilder builder, @NotNull final IncludeRule includeRule) throws IOException, VcsException {
        ClearCaseSupport.this.buildPatch(root, fromVersion, toVersion, builder, includeRule);
      }

      public void dispose() {
        //nothing to do
      }
    };
  }

  @Override
  public TestConnectionSupport getTestConnectionSupport() {
    return this;
  }

  private static interface ConnectionProcessor {
    void process(@NotNull final ClearCaseConnection connection) throws VcsException;
  }

  public void updateParameters(BuildStartContext context) {
    final SRunningBuild build = context.getBuild();
    try {
      //collect all clearcase's roots and populate current ConfigSpecs for each
      for (final VcsRootEntry entry : build.getVcsRootEntries()) {
        if (getName().equals(entry.getVcsRoot().getVcsName())) { //looking for clearcase only
          final String viewPath = trim(entry.getVcsRoot().getProperty(Constants.CC_VIEW_PATH));
          final File viewRoot = new File(viewPath);
          if (viewRoot.exists()) {
            try {
              final CCSnapshotView ccView = CCSnapshotView.init(viewRoot);
              LOG.debug(String.format("The \"%s\" view initialized", ccView));
              final StringBuffer specsBuffer = new StringBuffer();
              for (String spec : ccView.getConfigSpec()) {
                specsBuffer.append(spec).append("\n");
              }
              //pass config spec to agents
              final String configSpecParameterName = getConfigSpecParameterName(entry.getVcsRoot());
              final String configSpecParameterValue = specsBuffer.toString();
              context.addSharedParameter(configSpecParameterName, configSpecParameterValue);
              LOG.debug(String.format("added SharedParameter: %s=\"%s\"", configSpecParameterName, configSpecParameterValue));
              //pass tag to agents
              final String originalViewTagParameterName = getOriginalViewTagParameterName(entry.getVcsRoot());
              String originalViewTagParameterValue = ccView.getTag().trim();
              context.addSharedParameter(originalViewTagParameterName, originalViewTagParameterValue);
              LOG.debug(String.format("added SharedParameter: %s=\"%s\"", originalViewTagParameterName, originalViewTagParameterValue));
            } catch (CCException e) {
              LOG.error(e.getMessage(), e);
            }
          } else {
            LOG.error(String.format("The view's root directory \"%s\" does not exist. Could not get ConfigSpec of this VcsRoot", viewPath));
          }
        }
      }
    } catch (Throwable e) {
      LOG.error(e.getMessage(), e);
    }

  }

  public static String getOriginalViewTagParameterName(VcsRoot root) {
    return String.format("%s", String.format(Constants.AGENT_SOURCE_VIEW_TAG_PROP_PATTERN, root.getId()));
  }

  public static String getConfigSpecParameterName(VcsRoot root) {
    return String.format("%s", String.format(Constants.AGENT_CONFIGSPECS_SYS_PROP_PATTERN, root.getId()));
  }

  @NotNull
  public Collection<String> getParametersAvailableOnAgent(@NotNull SBuild build) {
    return Collections.<String> emptyList();
  }

  static String trim(String string) {
    if (string != null) {
      return string.trim();
    }
    return null;
  }

  public boolean isClearCaseClientNotFound() {
    return !Util.canRun(Constants.CLEARTOOL_CHECK_AVAILABLE_COMMAND);
  }

  @NotNull
  public Pattern[] getIgnoreErrorPatterns() {
    if (myIgnoreErrorPatterns == null) {
      final ArrayList<Pattern> patterns = new ArrayList<Pattern>();
      final String prop = TeamCityProperties.getPropertyOrNull(Constants.TEAMCITY_PROPERTY_IGNORE_ERROR_PATTERN);
      if (prop != null && prop.trim().length() > 0) {
        for (String pstr : prop.split("[;:]")) {
          if (pstr.trim().length() > 0) {
            try {
              patterns.add(Pattern.compile(pstr, Pattern.DOTALL | Pattern.MULTILINE));
            } catch (PatternSyntaxException e) {
              LOG.error(e.getMessage());
            }
          }
        }
      }
      myIgnoreErrorPatterns = patterns.toArray(new Pattern[patterns.size()]);
    }
    return myIgnoreErrorPatterns;
  }

}
