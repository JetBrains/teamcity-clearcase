/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Ref;
import com.intellij.util.Consumer;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jetbrains.buildServer.Used;
import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseValidation.IValidation;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseValidation.ValidationComposite;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpec;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpecLoadRule;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpecParseUtil;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.ClearCaseInteractiveProcess;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.ClearCaseInteractiveProcessPool;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.structure.ClearCaseStructureCache;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Version;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.util.*;
import jetbrains.buildServer.util.filters.Filter;
import jetbrains.buildServer.util.filters.FilterUtil;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.clearcase.CCException;
import jetbrains.buildServer.vcs.clearcase.CCSnapshotView;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;
import jetbrains.buildServer.vcs.patches.PatchBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.vcs.clearcase.Constants.*;

public class ClearCaseSupport extends ServerVcsSupport implements VcsPersonalSupport, LabelingSupport, VcsFileContentProvider,
                                                                  CollectSingleStateChangesByIncludeRules, BuildPatchByIncludeRules,
                                                                  TestConnectionSupport, BuildStartContextProcessor,
                                                                  VcsRootBasedMappingProvider,
                                                                  CollectSingleStateChangesBetweenRoots,
                                                                  ListDirectChildrenPolicy {

  private static final Logger LOG = Logger.getInstance(ClearCaseSupport.class.getName());

  private static final boolean USE_CC_CACHE = !TeamCityProperties.getBoolean("clearcase.disable.caches");
  private static final Pattern COLON_OR_SEMICOLON_PATTERN = Pattern.compile("[;:]");

  private @Nullable ClearCaseStructureCache myCache;

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
  }

  @NotNull
  public static ViewPath getViewPath(@NotNull final VcsRoot vcsRoot) throws VcsException, IOException {
    final String viewPath = vcsRoot.getProperty(Constants.VIEW_PATH);
    if (viewPath != null && trim(viewPath).length() != 0) {
      return getViewPath(viewPath);
    }
    return new ViewPath(vcsRoot.getProperty(Constants.CC_VIEW_PATH), vcsRoot.getProperty(Constants.RELATIVE_PATH));
  }

  @NotNull
  public static ViewPath getRootPath(@NotNull final VcsRoot vcsRoot) throws VcsException, IOException {
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
  public static ViewPath getViewPath(@NotNull final String viewPath) throws VcsException, IOException {
    final String ccViewRoot = ClearCaseConnection.getClearCaseViewRoot(viewPath);
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

  public void withConnection(final VcsRoot root,
                             final FileRule includeRule,
                             @Nullable final ConfigSpecLoadRule loadRule,
                             @NotNull final ConnectionProcessor processor) throws VcsException, IOException {
    withConnection(root, includeRule, false, loadRule, processor);
  }

  public void withConnection(final VcsRoot root,
                             final FileRule includeRule,
                             final boolean checkCSChange,
                             @Nullable final ConfigSpecLoadRule loadRule,
                             @NotNull final ConnectionProcessor processor) throws VcsException, IOException {
    final ViewPath viewPath = getViewPath(root);
    if (includeRule.getFrom().length() > 0) {
      viewPath.setIncludeRuleFrom(includeRule);
    }
    doWithConnection(viewPath, root, checkCSChange, processor);
  }

  public void doWithConnection(final ViewPath viewPath, final VcsRoot root, final boolean checkCSChange, final ConnectionProcessor processor) throws IOException, VcsException {
    ClearCaseInteractiveProcessPool.doWithProcess(viewPath, new ClearCaseInteractiveProcessPool.ProcessRunnable() {
      public void run(@NotNull final ClearCaseInteractiveProcess process) throws IOException, VcsException {
        processor.process(new ClearCaseConnection(viewPath, process, myCache, root, checkCSChange));
      }
    });
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

  private VcsChange addChange(final HistoryElement element,
                              final String childFullPath,
                              final ClearCaseConnection connection,
                              final VcsChangeInfo.Type type,
                              final String beforeVersion,
                              final String afterVersion,
                              final MultiMap<CCModificationKey, VcsChange> key2changes) throws VcsException {
    final CCModificationKey modificationKey = new CCModificationKey(Revision.fromChange(element.getChangeInfo()), element.getUser(), element.getActivity());
    final VcsChange change = createChange(type, connection, beforeVersion, afterVersion, childFullPath);

    key2changes.putValue(modificationKey, change);

    final CCModificationKey realKey = findKey(modificationKey, key2changes);
    if (realKey != null) {
      realKey.getCommentHolder().update(element.getActivity(), element.getComment(), connection.getVersionDescription(childFullPath, !isFile(type)));
      if (!modificationKey.getVersion().beforeOrEquals(realKey.getVersion())) { // must keep the greatest eventId
        realKey.setVersion(modificationKey.getVersion());
      }
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

  public void buildPatch(final VcsRoot root, final Revision fromVersion, final Revision toVersion, final PatchBuilder builder, final IncludeRule includeRule) throws IOException, VcsException {
    withConnection(root, includeRule, true, null, new ConnectionProcessor() {
      public void process(@NotNull final ClearCaseConnection connection) throws VcsException, IOException {
        buildPatchForConnection(builder, fromVersion, toVersion, connection);
      }
    });
  }

  private void buildPatchForConnection(PatchBuilder builder, Revision fromVersion, Revision toVersion, ClearCaseConnection connection) throws IOException, VcsException {
    try {
      final boolean useCache = USE_CC_CACHE && !connection.getConfigSpec().hasLabelBasedVersionSelector();
      new CCPatchProvider(connection, useCache).buildPatch(builder, fromVersion, toVersion);
    }
    catch (final ExecutionException e) {
      throw new VcsException(e);
    }
  }
  
  private static byte[] getFileContent(final ClearCaseConnection connection, final String filePath) throws VcsException {
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
  public byte[] getContent(@NotNull final VcsModification vcsModification, @NotNull final VcsChangeInfo change, @NotNull final VcsChangeInfo.ContentType contentType, @NotNull final VcsRoot vcsRoot) throws VcsException {
    final Ref<byte[]> result = new Ref<byte[]>();
    try {
      withConnection(vcsRoot, IncludeRule.createDefaultInstance(), null, new ConnectionProcessor() {
        public void process(@NotNull final ClearCaseConnection connection) throws VcsException {
          final String filePath = new File(connection.getViewWholePath()).getParent() + File.separator + (contentType == VcsChangeInfo.ContentType.BEFORE_CHANGE ? change.getBeforeChangeRevisionNumber() : change.getAfterChangeRevisionNumber());
          result.set(getFileContent(connection, filePath));
        }
      });
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
    return result.get();
  }

  @NotNull
  public byte[] getContent(@NotNull final String filePath, @NotNull final VcsRoot versionedRoot, @NotNull final String version) throws VcsException {
    final Ref<byte[]> result = new Ref<byte[]>();
    try {
      withConnection(versionedRoot, IncludeRule.createDefaultInstance(), null, new ConnectionProcessor() {
        public void process(@NotNull final ClearCaseConnection connection) throws VcsException {
          final String preparedPath = CCPathElement.normalizeSeparators(filePath);
          try {
            connection.collectChangesToIgnore(Revision.fromNotNullString(version));
            result.set(doGetContent(preparedPath, connection));
          }
          catch (final ParseException e) {
            throw new VcsException(e);
          }
          catch (final VcsException e) { // http://youtrack.jetbrains.com/issue/TW-20973
            int sepPos = preparedPath.indexOf(File.separator);
            while (sepPos != -1) {
              try {
                result.set(doGetContent(preparedPath.substring(sepPos + 1), connection));
                return;
              }
              catch (final VcsException ignore) {}
              sepPos = preparedPath.indexOf(File.separator, sepPos + 1);
            }
            throw e;
          }
        }
      });
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
    return result.get();
  }

  @NotNull
  private static byte[] doGetContent(@NotNull final String filePath, @NotNull final ClearCaseConnection connection) throws VcsException {
    final String path = new File(connection.getViewWholePath()).getParent() + File.separator +
                        connection.getObjectRelativePathWithVersions(connection.getViewWholePath() + File.separator + filePath, true);
    return getFileContent(connection, path);
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
        final ValidationComposite composite = createValidationComposite(null, null);
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
  @Override
  @SuppressWarnings("deprecation")
  public String getCurrentVersion(@NotNull final VcsRoot root) throws VcsException {
    final Ref<String> result = new Ref<String>();
    try {
      withConnection(root, IncludeRule.createDefaultInstance(), null, new ConnectionProcessor() {
        public void process(@NotNull final ClearCaseConnection connection) throws VcsException, IOException {
          result.set(connection.getCurrentRevision().asString());
        }
      });
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
    return result.get();
  }

  @Override
  public boolean isCurrentVersionExpensive() {
    return false;
  }

  @NotNull
  public String getVersionDisplayName(@NotNull final String version, @NotNull final VcsRoot root) throws VcsException {
    try {
      return Revision.fromNotNullString(version).asDisplayString();
    }
    catch (final ParseException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  public Comparator<String> getVersionComparator() {
    return new Comparator<String>() {
      public int compare(@NotNull final String versionString1, @NotNull final String versionString2) {
        try {
          final Revision version1 = Revision.fromNotNullString(versionString1);
          final Revision version2 = Revision.fromNotNullString(versionString2);
          return version1.beforeOrEquals(version2) ? (version2.beforeOrEquals(version1) ? 0 : -1) : 1;
        }
        catch (final ParseException e) {
          ExceptionUtil.rethrowAsRuntimeException(e);
          return 0;
        }
      }
    };
  }

  @Override
  public boolean isAgentSideCheckoutAvailable() {
    return true;
  }

  @NotNull
  public String describeVcsRoot(@NotNull VcsRoot vcsRoot) {
    try {
      return Constants.NAME + ": " + getViewPath(vcsRoot).getWholePath();
    }
    catch (Exception e) {
      return Constants.NAME;
    }
  }

  public String testConnection(final @NotNull VcsRoot vcsRoot) throws VcsException {
    final String[] validationResult = new String[] { "Passed" };
    //validate in general
    final ValidationComposite composite = createValidationComposite(vcsRoot, validationResult);
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

  @NotNull
  private ValidationComposite createValidationComposite(@Nullable final VcsRoot vcsRoot, @Nullable final String[] validationResult) {
    return new ValidationComposite(
      new ClearCaseValidation.ClearcaseViewRootPathValidator(),
      new ClearCaseValidation.ClearcaseViewRelativePathValidator(),
      new ClearCaseValidation.CleartoolValidator(),
      new ClearCaseValidation.ClearcaseConfigurationValidator(),
      new ClearCaseValidation.ClearcaseViewValidator(),
      new IValidation() {
        public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
          if (vcsRoot == null || validationResult == null) return true;
          try {
            validationResult[0] = ClearCaseConnection.testConnection(vcsRoot);
          }
          catch (final Exception e) {
            validationResultBuffer.add(
              //it fired by "Relative path..." setting because others already checked hard I guess...
              new InvalidProperty(Constants.RELATIVE_PATH, String.format(Messages.getString("ClearCaseSupport.clearcase_view_relative_path_is_not_under_configspec_loading_rules"), e.getMessage()))
            );
            LOG.info(e.toString());
            LOG.debug(e.toString(), e);
            return false;
          }
          return true;
        }

        public String getDescription() {
          return "Summary functionality check";
        }
      }
    );
  }

  @Nullable
  public Map<String, String> getDefaultVcsProperties() {
    return new HashMap<String, String>() {{
      put(Constants.BRANCH_PROVIDER, Constants.BRANCH_PROVIDER_AUTO);
    }};
  }

  public Collection<VcsClientMapping> getClientMapping(@NotNull final VcsRoot vcsRoot) throws VcsException {
    try {
      return Collections.singleton(new VcsClientMapping(getViewPath(vcsRoot).getWholePath(), ""));
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  public Collection<String> mapFullPath(@NotNull final VcsRootEntry rootEntry, @NotNull final String fullPath) {
    final ViewPath viewPath;

    try {
      viewPath = getViewPath(rootEntry.getVcsRoot());
    }
    catch (final Exception e) {
      LOG.debug("CC.MapFullPath: View path not defined: " + e.getLocalizedMessage());
      return Collections.emptySet();
    }

    final File viewPathFile = cutOffVobsDir(viewPath.getWholePath(), viewPath);
    if (viewPathFile == null) { // actually impossible
      LOG.debug("CC.MapFullPath: Unknown error");
      return Collections.emptySet();
    }

    final File fullPathFile = cutOffVobsDir(fullPath, viewPath);
    if (fullPathFile == null) {
      LOG.debug("CC.MapFullPath: File \"" + fullPath + "\" is not under view \"" + viewPath.getWholePath() + "\"");
      return Collections.emptySet();
    }

    final String relativePath = getRelativePathIfAncestor(viewPathFile, fullPathFile);
    if (relativePath != null) {
      LOG.debug("CC.MapFullPath: File \"" + fullPathFile.getAbsolutePath() + "\" is under \"" + viewPathFile.getAbsolutePath() + "\" result is \"" + relativePath + "\"");
      return Collections.singleton(relativePath);
    }
    else {
      LOG.debug("CC.MapFullPath: File \"" + fullPathFile.getAbsolutePath() + "\" is not under \"" + viewPathFile.getAbsolutePath() + "\"");
      return Collections.emptySet();
    }
  }

  @Nullable
  private static File cutOffVobsDir(@NotNull final String filePath, @NotNull final ViewPath viewPath) {
    final File clearCaseViewPathFile = viewPath.getClearCaseViewPathFile();
    final File file = new File(filePath);
    String relativePath = isAbsolute(file) ? getRelativePathIfAncestor(clearCaseViewPathFile, file) : file.getPath();
    if (relativePath == null) return null;
    if (StringUtil.startsWithIgnoreCase(relativePath.replace('\\','/'), Constants.VOBS)) {
      relativePath = relativePath.substring(Constants.VOBS.length());
    }
    return new File(clearCaseViewPathFile, relativePath);
  }

  // File path can be "/bla-bla-bla" when server is on Windows and can be "C:\bla-bla-bla" when server is on Linux/Mac OS - we must handle this
  private static boolean isAbsolute(@NotNull final File file) {
    return file.isAbsolute() || file.getPath().startsWith(File.separator) || file.getPath().startsWith(":" + File.separator, 1);
  }

  @Nullable
  private static String getRelativePathIfAncestor(@NotNull final File parentFile, @NotNull final File childFile) {
    return FileUtil.isAncestor(parentFile, childFile, false)
           ? FileUtil.getRelativePath(parentFile.getAbsolutePath(), childFile.getAbsolutePath(), File.separatorChar)
           : null;
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

  public List<ModificationData> collectChanges(final VcsRoot root, final Revision fromVersion, final Revision currentVersion, final IncludeRule includeRule) throws VcsException {
    LOG.debug(String.format("Attempt connect to '%s'", root.describe(true)));
    final Ref<List<ModificationData>> result = new Ref<List<ModificationData>>();
    try {
      withConnection(root, includeRule, null, new ConnectionProcessor() {
        public void process(@NotNull final ClearCaseConnection connection) throws VcsException {
          try {
            result.set(collectChangesWithConnection(root, fromVersion, currentVersion, connection));
          }
          catch (VcsException e) {
            LOG.debug(String.format("Could not establish connection: %s", e.getMessage()));
            throw e;
          }
        }
      });
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
    return result.get();
  }

  private List<ModificationData> collectChangesWithConnection(VcsRoot root, Revision fromVersion, Revision currentVersion, ClearCaseConnection connection) throws VcsException {
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

          if (changes.isEmpty()) continue;

          final DateRevision version = key.getVersion();
          list.add(new ModificationData(
            version.getDate(), changes, key.getCommentHolder().toString(), key.getUser(), root, version.asString(), version.asDisplayString()
          ));
        }
      } catch (final Exception e) {
        throw new VcsException(e);
      }

      Collections.sort(list, new Comparator<ModificationData>() {
        public int compare(final ModificationData o1, final ModificationData o2) {
          return o1.getVcsDate().compareTo(o2.getVcsDate());
        }
      });

      return list;
    }
    finally {
      LOG.debug("Collecting changes was finished.");
    }
  }

  @NotNull
  public String label(@NotNull final String label, @NotNull final String version, @NotNull final VcsRoot root, @NotNull final CheckoutRules checkoutRules) throws VcsException {
    try {
      final Revision revision = Revision.fromNotNullString(version);

      createLabel(label, root);

      final VersionProcessor labeler = getClearCaseLabeler(label);
      final ConnectionProcessor childrenProcessor = getChildrenProcessor(revision, labeler);

      for (IncludeRule includeRule : checkoutRules.getRootIncludeRules()) {
        withConnection(root, includeRule, null, childrenProcessor);
        withRootConnection(root, getParentsProcessor(revision, labeler, createPath(root, includeRule)));
      }
      return label;
    }
    catch (ParseException e) {
      throw new VcsException(e);
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
  }

  private String createPath(@NotNull final VcsRoot root, @NotNull final IncludeRule includeRule) throws VcsException {
    try {
      final ViewPath viewPath = getViewPath(root);
      viewPath.setIncludeRuleFrom(includeRule);
      return viewPath.getWholePath();
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
  }

  private ConnectionProcessor getParentsProcessor(final Revision version, final VersionProcessor labeler, final String path) {
    return new ConnectionProcessor() {
      public void process(@NotNull final ClearCaseConnection connection) throws VcsException {
        connection.processAllParents(version, labeler, path);
      }
    };
  }

  private ConnectionProcessor getChildrenProcessor(final Revision version, final VersionProcessor labeler) {
    return new ConnectionProcessor() {
      public void process(@NotNull final ClearCaseConnection connection) throws VcsException {
        connection.processAllVersions(version, labeler, true, true);
      }
    };
  }

  private void withRootConnection(@NotNull final VcsRoot root, @NotNull final ConnectionProcessor processor) throws VcsException, IOException {
    doWithConnection(getRootPath(root), root, false, processor);
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
    try {
      ClearCaseInteractiveProcessPool.doWithProcess(getViewPath(root).getWholePath(), new ClearCaseInteractiveProcessPool.ProcessRunnable() {
        public void run(@NotNull final ClearCaseInteractiveProcess process) throws VcsException {
          final boolean useGlobalLabel = "true".equals(root.getProperty(Constants.USE_GLOBAL_LABEL));
          try {
            final List<String> parameters = new ArrayList<String>();
            parameters.add("mklbtype");
            if (useGlobalLabel) {
              parameters.add("-global");
            }
            if (ClearCaseConnection.isLabelExists(process, label)) {
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
            try {
              process.executeAndReturnProcessInput(makeArray(parameters)).close();
            } catch (final IOException ignore) {}
          }
          catch (final Exception e) {
            if (!e.getLocalizedMessage().contains("already exists")) {
              throw new VcsException(e);//e;
            }
          }
        }
      });
    }
    catch (final IOException e) {
      throw new VcsException(e);
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
    if (TeamCityProperties.getBoolean("clearcase.sources.update.is.not.possible.if.changes.not.found")) {
      return false;
    }

    try {
      final ViewPath viewPath = getViewPath(root);
      return ClearCaseInteractiveProcessPool.doWithProcess(viewPath.getWholePath(), new ClearCaseInteractiveProcessPool.ProcessComputable<Boolean>() {
        public Boolean compute(@NotNull final ClearCaseInteractiveProcess process) throws IOException, VcsException {
          final ConfigSpec spec = ConfigSpecParseUtil.getConfigSpec(viewPath, process);
          // as far we still cannot detect label moving,
          // have to use all resources set for the patch creation
          return spec != null && spec.hasLabelBasedVersionSelector();
        }
      });
    }
    catch (final VcsException e) {
      LOG.warnAndDebugDetails("Failed to run sourcesUpdatePossibleIfChangesNotFound()", e);
    }
    catch (final IOException e) {
      LOG.warnAndDebugDetails("Failed to run sourcesUpdatePossibleIfChangesNotFound()", e);
    }
    return false;
  }

  public static void consumeBranches(@NotNull final VcsRoot root, @NotNull final Consumer<Collection<String>> consumer) {
    if (BRANCH_PROVIDER_CUSTOM.equals(root.getProperty(BRANCH_PROVIDER, BRANCH_PROVIDER_AUTO))) {
      consumer.consume(StringUtil.split(StringUtil.emptyIfNull(root.getProperty(BRANCHES))));
    }
    else {
      consumer.consume(null);
    }
  }


  @NotNull
  @Override
  public Map<String, String> getCheckoutProperties(@NotNull final VcsRoot root) {
    final Map<String, String> result = new HashMap<String, String>(4);

    try {
      final ViewPath viewPath = getViewPath(root);

      result.put(CC_VIEW_PATH, viewPath.getClearCaseViewPath()); // normalized path
      result.put(RELATIVE_PATH, viewPath.getRelativePathWithinTheView()); // normalized path

      consumeBranches(root, new Consumer<Collection<String>>() {
        public void consume(@Nullable final Collection<String> branches) {
          if (branches == null) { // auto detecting
            result.put(BRANCH_PROVIDER, BRANCH_PROVIDER_AUTO);
          }
          else {
            result.put(BRANCH_PROVIDER, BRANCH_PROVIDER_CUSTOM);
            result.put(BRANCHES, StringUtil.join(new TreeSet<String>(branches), ",")); // join branches in nomalized order
          }
        }
      });
    }
    catch (final Exception e) {
      LOG.warnAndDebugDetails("Failed to get repository properties", e);
    }

    return result;
  }

  @NotNull
  public List<ModificationData> collectChanges(@Nullable final VcsRoot fromRoot,
                                               @NotNull final String fromVersion,
                                               @NotNull final VcsRoot toRoot,
                                               @Nullable final String toVersion,
                                               @NotNull final CheckoutRules checkoutRules) throws VcsException {
    try {
      return fromRoot == null || getViewPath(fromRoot).equals(getViewPath(toRoot))
             ? VcsSupportUtil.collectBuildChanges(toRoot, fromVersion, toVersion, checkoutRules, this)
             : Collections.<ModificationData>emptyList();
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  public IncludeRuleChangeCollector getChangeCollector(@NotNull final VcsRoot root, @NotNull final String fromVersion, @Nullable final String currentVersion) throws VcsException {
    return new IncludeRuleChangeCollector() {
      @NotNull
      public List<ModificationData> collectChanges(@NotNull final IncludeRule includeRule) throws VcsException {
        try {
          return ClearCaseSupport.this.collectChanges(root, Revision.fromNotNullString(fromVersion), Revision.fromString(currentVersion), includeRule);
        }
        catch (final ParseException e) {
          throw new VcsException(e);
        }
      }

      public void dispose() {}
    };
  }

  @NotNull
  public IncludeRulePatchBuilder getPatchBuilder(@NotNull final VcsRoot root, @Nullable final String fromVersion, @NotNull final String toVersion) {
    return new IncludeRulePatchBuilder() {
      public void buildPatch(@NotNull final PatchBuilder builder, @NotNull final IncludeRule includeRule) throws IOException, VcsException {
        try {
          ClearCaseSupport.this.buildPatch(root, Revision.fromString(fromVersion), Revision.fromNotNullString(toVersion), builder, includeRule);
        }
        catch (final ParseException e) {
          throw new VcsException(e);
        }
      }

      public void dispose() {}
    };
  }

  @Override
  public TestConnectionSupport getTestConnectionSupport() {
    return this;
  }

  @Override
  public ListFilesPolicy getListFilesPolicy() {
    return this;
  }

  @NotNull
  public Collection<VcsFileData> listFiles(@NotNull final VcsRoot root, @NotNull final String directoryPath) throws VcsException {
    final Ref<Collection<VcsFileData>> result = new Ref<Collection<VcsFileData>>();
    try {
      withConnection(root, IncludeRule.createDefaultInstance(), null, new ConnectionProcessor() {
        public void process(@NotNull final ClearCaseConnection connection) throws VcsException, IOException {
          final String dirFullPath = new File(getViewPath(root).getWholePath(), directoryPath).getAbsolutePath();
          final Version dirVersion = connection.getLastVersion(dirFullPath, false);
          if (dirVersion == null) {
            result.set(Collections.<VcsFileData>emptySet());
            return;
          }

          final String dirPathWithVersion = dirFullPath + CCParseUtil.CC_VERSION_SEPARATOR + dirVersion.getWholeName();

          final Collection<VcsFileData> files = new ArrayList<VcsFileData>();
          for (final SimpleDirectoryChildElement child : connection.getChildren(dirPathWithVersion)) {
            files.add(new VcsFileData(child.getName(), child.getType() == SimpleDirectoryChildElement.Type.DIRECTORY));
          }

          result.set(files);
        }
      });
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
    return result.get();
  }

  @NotNull
  public static SortedSet<String> detectBranches(@NotNull final ViewPath viewPath) throws VcsException, IOException {
    return ClearCaseInteractiveProcessPool.doWithProcess(viewPath.getWholePath(), new ClearCaseInteractiveProcessPool.ProcessComputable<SortedSet<String>>() {
      public SortedSet<String> compute(@NotNull final ClearCaseInteractiveProcess process) throws IOException, VcsException {
        return ConfigSpecParseUtil.getConfigSpec(viewPath, process).getBranches();
      }
    });
  }

  public static interface ConnectionProcessor {
    void process(@NotNull ClearCaseConnection connection) throws VcsException, IOException;
  }

  public void updateParameters(@NotNull BuildStartContext context) {
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
    return Collections.emptyList();
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
    final ArrayList<Pattern> patterns = new ArrayList<Pattern>();
    final String prop = TeamCityProperties.getPropertyOrNull(Constants.TEAMCITY_PROPERTY_IGNORE_ERROR_PATTERN);
    if (prop != null && prop.trim().length() > 0) {
      for (String pstr : COLON_OR_SEMICOLON_PATTERN.split(prop)) {
        if (pstr.trim().length() > 0) {
          try {
            patterns.add(Pattern.compile(pstr, Pattern.DOTALL | Pattern.MULTILINE));
          } catch (PatternSyntaxException e) {
            LOG.error(e.toString());
          }
        }
      }
    }
    return patterns.toArray(new Pattern[patterns.size()]);
  }
}
