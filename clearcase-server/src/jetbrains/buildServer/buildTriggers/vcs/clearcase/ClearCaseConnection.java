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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import jetbrains.buildServer.CommandLineExecutor;
import jetbrains.buildServer.ExecResult;
import jetbrains.buildServer.ProcessListener;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpec;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec.ConfigSpecParseUtil;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.ClearCaseFacade;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.InteractiveProcess;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.process.InteractiveProcessFacade;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.structure.ClearCaseStructureCache;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Version;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.VersionTree;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.MultiMap;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.CTool;
import jetbrains.buildServer.vcs.clearcase.CTool.VersionParser;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;

@SuppressWarnings({ "SimplifiableIfStatement" })
public class ClearCaseConnection {
  @NonNls
  private static final String PATH = "%path%";

  private final ViewPath myViewPath;

  private final boolean myUCMSupported;

  private static final Map<String, Semaphore> viewName2Semaphore = new ConcurrentHashMap<String, Semaphore>();

  private static final String UNIX_VIEW_PATH_PREFIX = "/view/";

  private static final Logger LOG = Logger.getLogger(ClearCaseConnection.class);

  public final static String DELIMITER = "#--#";

  @NonNls
  public static final String LINE_END_DELIMITER = "###----###";
  public final static String FORMAT = "%u" //user
      + DELIMITER + "%Nd" //date
      + DELIMITER + "%En" //object name
      + DELIMITER + "%m" //object kind    
      + DELIMITER + "%Vn" //objectversion
      + DELIMITER + "%o" //operation
      + DELIMITER + "%e" //event    
      + DELIMITER + "%Nc" //comment
      + DELIMITER + "%[activity]p" //activity    
      + LINE_END_DELIMITER + "\\n";

  private final MultiMap<String, HistoryElement> myChangesToIgnore = new MultiMap<String, HistoryElement>();

  private final MultiMap<String, HistoryElement> myDeletedVersions = new MultiMap<String, HistoryElement>();

  private final ConfigSpec myConfigSpec;

  private static final String UPDATE_LOG = "teamcity.clearcase.update.result.log";

  private static HashMap<String, InteractiveProcessFacade> ourViewProcesses = new HashMap<String, InteractiveProcessFacade>();

  public static ClearCaseFacade ourProcessExecutor = new ClearCaseFacade() {
    public ExecResult execute(final GeneralCommandLine commandLine, final ProcessListener listener) throws ExecutionException {
      CommandLineExecutor commandLineConnection = new CommandLineExecutor(commandLine);
      commandLineConnection.addListener(listener);
      return commandLineConnection.runProcess();
    }

    public InteractiveProcessFacade createProcess(final GeneralCommandLine generalCommandLine) throws ExecutionException {
      return createInteractiveProcess(generalCommandLine.createProcess());
    }
  };

  private final ClearCaseStructureCache myCache;
  private final VcsRoot myRoot;
  private final boolean myConfigSpecWasChanged;

  @NotNull
  private final Map<String, List<SimpleDirectoryChildElement>> myDirectoryContentCache = new HashMap<String, List<SimpleDirectoryChildElement>>();
  @NotNull
  private final Map<String, Version> myDirectoryVersionCache = new HashMap<String, Version>();

  public boolean isConfigSpecWasChanged() {
    return myConfigSpecWasChanged;
  }

  public ClearCaseConnection(ViewPath viewPath, boolean ucmSupported, ClearCaseStructureCache cache, VcsRoot root, final boolean checkCSChange) throws Exception {

    // Explanation of config specs at:
    // http://www.philforhumanity.com/ClearCase_Support_17.html

    myCache = cache;
    myRoot = root;

    myUCMSupported = ucmSupported;

    myViewPath = viewPath;

    if (!isClearCaseView(myViewPath.getClearCaseViewPath())) {
      throw new VcsException("Invalid ClearCase view: \"" + myViewPath.getClearCaseViewPath() + "\"");
    }

    final File cacheDir = myCache == null ? null : myCache.getCacheDir(root, true);

    final File configSpecFile = cacheDir != null ? new File(cacheDir, "cs") : null;

    ConfigSpec oldConfigSpec = null;
    if (checkCSChange && configSpecFile != null && configSpecFile.isFile()) {
      oldConfigSpec = ConfigSpecParseUtil.getConfigSpecFromStream(myViewPath.getClearCaseViewPathFile(), new FileInputStream(configSpecFile), configSpecFile);
    }

    myConfigSpec = checkCSChange && configSpecFile != null ? ConfigSpecParseUtil.getAndSaveConfigSpec(myViewPath, configSpecFile) : ConfigSpecParseUtil.getConfigSpec(myViewPath);

    myConfigSpec.setViewIsDynamic(isViewIsDynamic());

    myConfigSpecWasChanged = checkCSChange && configSpecFile != null && !myConfigSpec.equals(oldConfigSpec);

    if (myConfigSpecWasChanged) {
      myCache.clearCaches(root);
    }

    if (!myConfigSpec.isUnderLoadRules(getClearCaseViewPath(), myViewPath.getWholePath())) {
      throw new VcsException("The path \"" + myViewPath.getWholePath() + "\" is not loaded by ClearCase view \"" + myViewPath.getClearCaseViewPath() + "\" according to its config spec.");
    }

    //??
    updateCurrentView();

    //create batch executor
    getInteractiveProcess(getViewWholePath());

  }

  public static InteractiveProcessFacade getInteractiveProcess(final @NotNull String viewPath) throws IOException {
    synchronized (ourViewProcesses) {
      InteractiveProcessFacade cached = ourViewProcesses.get(viewPath);
      if (cached == null) {
        //looking for parent executor
        File parent = new File(viewPath).getParentFile();
        while (parent != null) {
          try {
            cached = ourViewProcesses.get(CCPathElement.normalizePath(parent.getAbsolutePath().trim()));
            if (cached != null) {
              return cached;
            }
            parent = parent.getParentFile();
          } catch (VcsException e) {
            IOException io = new IOException(e.getMessage());
            io.initCause(e);
            throw io;
          }
        }
        //create new
        cached = createProcessFacade(viewPath);
        ourViewProcesses.put(viewPath, cached);
      }
      return cached;
    }
  }

  private InteractiveProcessFacade renewProcess(final @NotNull String viewPath) throws IOException {
    InteractiveProcessFacade cached = getInteractiveProcess(viewPath);
    if (cached != null) {
      cached.destroy();
      ourViewProcesses.remove(viewPath);
      LOG.debug(String.format("Interactive Process of '%s' has been dropt. Recreated."));
    }
    return getInteractiveProcess(viewPath);
  }

  private static InteractiveProcessFacade createProcessFacade(final @NotNull String viewPath) throws IOException {
    final GeneralCommandLine generalCommandLine = new GeneralCommandLine();
    generalCommandLine.setExePath("cleartool");
    generalCommandLine.addParameter("-status");
    generalCommandLine.setWorkDirectory(viewPath);
    try {
      return ourProcessExecutor.createProcess(generalCommandLine);
    } catch (ExecutionException e) {
      IOException io = new IOException(e.getMessage());
      io.initCause(e);
      throw io;
    }
  }

  public boolean isUCM() {
    return myUCMSupported;
  }

  private static InteractiveProcess createInteractiveProcess(final Process process) {
    return new ClearCaseInteractiveProcess(process);
  }

  public void dispose() throws IOException {
    final InteractiveProcessFacade process = ourViewProcesses.remove(myViewPath);
    if (process != null) {
      process.destroy();
    }
  }

  public String getViewWholePath() {
    return myViewPath.getWholePath();
  }

  public ConfigSpec getConfigSpec() {
    return myConfigSpec;
  }

  @Nullable
  public DirectoryChildElement getLastVersionElement(final String pathWithoutVersion, final DirectoryChildElement.Type type) throws VcsException {
    final Version lastElementVersion = getLastVersion(pathWithoutVersion, DirectoryChildElement.Type.FILE.equals(type));

    if (lastElementVersion != null) {
      return new DirectoryChildElement(type, extractElementPath(pathWithoutVersion), lastElementVersion.getVersion(), pathWithoutVersion + CCParseUtil.CC_VERSION_SEPARATOR + lastElementVersion.getWholeName(), lastElementVersion.getWholeName(), pathWithoutVersion);
    } else {
      LOG.debug("ClearCase: last element version not found for " + pathWithoutVersion);
      return null;
    }
  }

  @Nullable
  public Version getLastVersion(@NotNull final String path, final boolean isFile) throws VcsException {
    if (isFile) {
      return doGetLastVersion(path, isFile);
    }
    if (!myDirectoryVersionCache.containsKey(path)) {
      myDirectoryVersionCache.put(path, doGetLastVersion(path, false));
    }
    return myDirectoryVersionCache.get(path);
  }

  @Nullable
  private Version doGetLastVersion(@NotNull final String path, final boolean isFile) throws VcsException {
    try {
      final VersionTree versionTree = new VersionTree();

      readVersionTree(path, versionTree, !isFile);

      return getLastVersion(path, versionTree, isFile);
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @Nullable
  private Version getLastVersion(final String path, final VersionTree versionTree, final boolean isFile) throws IOException, VcsException {
    final String elementPath = extractElementPath(path);

    if (myChangesToIgnore.containsKey(elementPath)) {
      final List<HistoryElement> historyElements = myChangesToIgnore.get(elementPath);
      if (historyElements != null) {
        for (HistoryElement element : historyElements) {
          LOG.debug("ClearCase: element " + elementPath + ", branch ignored: " + element.getObjectVersion());
          versionTree.pruneBranch(element.getObjectVersion());
        }
      }

    }

    return myConfigSpec.getCurrentVersion(getClearCaseViewPath(), getPathWithoutVersions(path), versionTree, isFile);
  }

  @NotNull
  private static String extractElementPath(@NotNull final String fullPath) {
    return CCPathElement.createPathWithoutVersions(CCPathElement.splitIntoPathElements(fullPath));
  }

  private VersionTree readVersionTree(final String path, final VersionTree versionTree, final boolean isDirPath) throws IOException, VcsException {
    final InputStream inputStream = executeAndReturnProcessInput(new String[] { "lsvtree", "-obs", "-all", insertDots(path, isDirPath) });
    final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    try {

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.trim().length() > 0) {
          String elementVersion = readVersion(line);
          versionTree.addVersion(elementVersion);
        }
      }

      final List<HistoryElement> deletedVersions = myDeletedVersions.get(getPathWithoutVersions(path));
      for (HistoryElement deletedVersion : deletedVersions) {
        versionTree.addVersion(normalizeVersion(deletedVersion.getObjectVersion()));
      }
    } finally {
      reader.close();
    }
    return versionTree;
  }

  public static String readVersion(final String line) {
    final int versSeparatorIndex = line.lastIndexOf(CCParseUtil.CC_VERSION_SEPARATOR);
    String result = line.substring(versSeparatorIndex + CCParseUtil.CC_VERSION_SEPARATOR.length());
    return normalizeVersion(result);
  }

  private static String normalizeVersion(String version) {
    if (version.startsWith(File.separator)) {
      version = version.substring(1);
    }
    return version;
  }

  @NotNull
  public HistoryElementIterator getChangesIterator(@NotNull final String fromVersion) throws IOException, VcsException {
    final List<String> lsHistoryOptions = getLSHistoryOptions();
    HistoryElementIterator iterator = new HistoryElementProvider(getChanges(fromVersion, lsHistoryOptions.get(0)));
    for (int i = 1; i < lsHistoryOptions.size(); i++) {
      iterator = new HistoryElementMerger(iterator, new HistoryElementProvider(getChanges(fromVersion, lsHistoryOptions.get(i))));
    }
    return iterator;
  }

  private InputStream getChanges(final String since, final String options) throws VcsException, IOException {
    final String viewWholePath = getViewWholePath();
    final String preparedOptions = options.replace(PATH, insertDots(viewWholePath, true));
    final ArrayList<String> optionList = new ArrayList<String>();
    optionList.add("lshistory");
    optionList.add("-eventid");
    optionList.add("-since");
    optionList.add(since);
    optionList.add("-fmt");
    optionList.add(FORMAT);
    optionList.addAll(Arrays.asList(Util.makeArguments(preparedOptions)));
    return getInteractiveProcess(viewWholePath).executeAndReturnProcessInput(ClearCaseSupport.makeArray(optionList));
  }

  @NotNull
  private List<String> getLSHistoryOptions() {
    final String lsHistoryOptionsString = getLSHistoryOptionsString();
    LOG.debug("Using the following options for \"lshistory\": " + lsHistoryOptionsString);
    return splitStringByVerticalBar(lsHistoryOptionsString);
  }

  @NotNull
  private List<String> splitStringByVerticalBar(@NotNull final String string) {
    final List<String> options = new ArrayList<String>();

    int startPos = 0;
    int endPos = findNextVerticalBarPos(string, startPos);

    while (endPos != -1) {
      options.add(string.substring(startPos, endPos));
      startPos = endPos + 1;
      endPos = findNextVerticalBarPos(string, startPos);
    }

    options.add(string.substring(startPos));

    return options;
  }

  private int findNextVerticalBarPos(final String string, final int startPos) {
    final int pos = string.indexOf('|', startPos);
    if (pos < 0)
      return -1;
    final int nextPos = pos + 1;
    if (nextPos < string.length() && string.charAt(nextPos) == '|') {
      return findNextVerticalBarPos(string, pos + 2);
    }
    return pos;
  }

  @NotNull
  private String getLSHistoryOptionsString() {
    final String vcsRootOptionsById = TeamCityProperties.getPropertyOrNull(String.format(Constants.LSHISTORY_VCS_ROOT_OPTIONS_BY_ID, myRoot.getId()));
    if (vcsRootOptionsById != null)
      return vcsRootOptionsById;

    final String defaultOptions = TeamCityProperties.getPropertyOrNull(Constants.LSHISTORY_DEFAULT_OPTIONS);
    if (defaultOptions != null)
      return defaultOptions;

    return "-all " + PATH;
  }

  public InputStream listDirectoryContent(final String dirPath) throws ExecutionException, IOException, VcsException {
    return executeAndReturnProcessInput(new String[] { "ls", "-long", insertDots(dirPath, true) });
  }

  public void loadFileContent(final File tempFile, final String line) throws ExecutionException, InterruptedException, IOException, VcsException {
    getInteractiveProcess(myViewPath.getWholePath())/*myProcess*/.copyFileContentTo(this, line, tempFile);
  }

  public void collectChangesToIgnore(final String lastVersion) throws VcsException {
    try {
      CCParseUtil.processChangedFiles(this, lastVersion, null, new ChangedFilesProcessor() {
        public void processChangedFile(final HistoryElement element) {
          myChangesToIgnore.putValue(element.getObjectName(), element);
          LOG.debug("Change was ignored: changed file " + element.getLogRepresentation());
        }

        public void processChangedDirectory(final HistoryElement element) {
          myChangesToIgnore.putValue(element.getObjectName(), element);
          LOG.debug("Change was ignored: changed directory " + element.getLogRepresentation());
        }

        public void processDestroyedFileVersion(final HistoryElement element) {
          myDeletedVersions.putValue(element.getObjectName(), element);
          LOG.debug("Change was ignored: deleted version of " + element.getLogRepresentation());
        }
      });
    } catch (ParseException e) {
      throw new VcsException(e);
    } catch (IOException e) {
      throw new VcsException(e);
    }

  }

  @Nullable
  public Version findVersion(final String objectPath, final String objectVersion, final boolean isDirPath) throws IOException, VcsException {
    final VersionTree versionTree = new VersionTree();

    readVersionTree(objectPath, versionTree, isDirPath);

    final String normalizedVersion = objectVersion.startsWith(CCParseUtil.CC_VERSION_SEPARATOR) ? objectVersion.substring(CCParseUtil.CC_VERSION_SEPARATOR.length()) : objectVersion;

    final Version versionByPath = versionTree.findVersionByPath(normalizedVersion);

    if (versionByPath == null) {
      LOG.debug("ClearCase: version by path not found for " + objectPath + " by " + normalizedVersion);
    }

    return versionByPath;
  }

  public boolean versionIsInsideView(String objectPath, final String objectVersion, final boolean isFile) throws IOException, VcsException {
    final String fullPathWithVersions = objectPath + CCParseUtil.CC_VERSION_SEPARATOR + File.separatorChar + CCPathElement.removeFirstSeparatorIfNeeded(objectVersion);
    final List<CCPathElement> pathElements = CCPathElement.splitIntoPathElements(fullPathWithVersions);

    return myConfigSpec.isVersionIsInsideView(this, pathElements, isFile);
  }

  public String testConnection() throws IOException, VcsException {
    final StringBuffer result = new StringBuffer();

    final String[] params;
    if (myUCMSupported) {
      params = new String[] { "lsstream", "-long" };
    } else {
      params = new String[] { "describe", insertDots(getViewWholePath(), true) };
    }

    final InputStream input = executeAndReturnProcessInput(params);
    final BufferedReader reader = new BufferedReader(new InputStreamReader(input));

    try {
      String line;
      while ((line = reader.readLine()) != null) {
        result.append(line).append('\n');
      }
    } finally {
      reader.close();
    }

    return result.toString();
  }

  private InputStream executeAndReturnProcessInput(final String[] params) throws IOException {
    if (params != null && params.length > 0) {
      final InteractiveProcessFacade interactiveProcess = getInteractiveProcess(myViewPath.getWholePath());
      if (interactiveProcess != null) {
        try {
          return interactiveProcess/*myProcess*/.executeAndReturnProcessInput(params);
        } catch (IOException e) {
          if (e.getMessage().contains("pipe is being closed outside")) {
            //process has been dropt. recreate
            renewProcess(myViewPath.getWholePath());
            return executeAndReturnProcessInput(params);
          }
        }
      } else {
        LOG.warn(String.format("Could not load InteractiveProcessFacade for '%s'", myViewPath.getWholePath()));
      }
    }
    return new ByteArrayInputStream("".getBytes());
  }

  public String getVersionDescription(final String fullPath, final boolean isDirPath) {
    try {
      String[] params = { "describe", "-fmt", "%c", "-pname", insertDots(fullPath, isDirPath) };
      final InputStream input = executeAndReturnProcessInput(params);
      final BufferedReader reader = new BufferedReader(new InputStreamReader(input));
      try {
        final String line = reader.readLine();
        if (line != null) {
          return line;
        } else {
          return "";
        }
      } finally {
        reader.close();
      }
    } catch (Exception e) {
      //ignore
      return "";
    }
  }

  public String getObjectRelativePathWithVersions(final String path, final boolean isFile) throws VcsException {
    return getRelativePathWithVersions(path, 0, 1, true, isFile);

  }

  public String getParentRelativePathWithVersions(final String path, final boolean isFile) throws VcsException {
    return getRelativePathWithVersions(path, 1, 1, true, isFile);

  }

  private String getRelativePathWithVersions(String path, final int skipAtEndCount, int skipAtBeginCount, final boolean appentVersion, final boolean isFile) throws VcsException {
    final List<CCPathElement> pathElementList = CCPathElement.splitIntoPathAntVersions(path, getViewWholePath(), skipAtBeginCount);
    for (int i = 0; i < pathElementList.size() - skipAtEndCount; i++) {
      final CCPathElement pathElement = pathElementList.get(i);
      if (appentVersion) {
        if (!pathElement.isIsFromViewPath() && pathElement.getVersion() == null) {
          final Version lastVersion = getLastVersion(CCPathElement.createPath(pathElementList, i + 1, appentVersion), isFile);
          if (lastVersion != null) {
            pathElement.setVersion(lastVersion.getWholeName());
          }
        }
      } else {
        pathElement.setVersion(null);
      }
    }

    return CCPathElement.createRelativePathWithVersions(pathElementList);
  }

  public String getPathWithoutVersions(String path) throws VcsException {
    return CCPathElement.createPathWithoutVersions(CCPathElement.splitIntoPathAntVersions(path, getViewWholePath(), 0));
  }

  public void updateCurrentView() throws VcsException {
    updateView(getViewWholePath(), true);
  }

  private boolean isViewIsDynamic() throws VcsException, IOException {
    return isViewIsDynamic(getViewWholePath());
  }

  public static boolean isViewIsDynamic(@NotNull final String viewPath) throws VcsException, IOException {
    final InputStream inputStream = getInteractiveProcess(viewPath).executeAndReturnProcessInput(/*params)executeSimpleProcess(viewPath, */new String[] { "lsview", "-cview", "-long" });
    final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

    try {
      String line = reader.readLine();
      while (line != null) {
        if (line.startsWith("View attributes:")) {
          return !line.contains("snapshot");
        }
        line = reader.readLine();
      }
    } finally {
      reader.close();
    }

    return true;
  }

  public static void updateView(final String viewPath, final boolean writeLog) throws VcsException {
    Semaphore semaphore;
    synchronized (viewName2Semaphore) {
      semaphore = viewName2Semaphore.get(viewPath);
      if (semaphore == null) {
        semaphore = new Semaphore(1);
        viewName2Semaphore.put(viewPath, semaphore);
      }
    }
    try {
      semaphore.acquire();
      final String log = writeLog ? UPDATE_LOG : (SystemInfo.isWindows ? "NUL" : "/dev/null");
      getInteractiveProcess(viewPath).executeAndReturnProcessInput(new String[] { "update", "-force", "-rename", "-log", log }).close();
    } catch (IOException e) {
      if (e.getLocalizedMessage().contains("is not a valid snapshot view path")) {
        //ignore, it is dynamic view
        LOG.debug("Please ignore the error above if you use dynamic view.");
      } else {
        throw new VcsException(e);
      }
    } catch (InterruptedException e) {
      throw new VcsException(e);
    } finally {
      semaphore.release();
      FileUtil.delete(new File(viewPath, UPDATE_LOG));
    }
  }

  String getObjectRelativePathWithoutVersions(final String path, final boolean isFile) throws VcsException {
    return getRelativePathWithVersions(path, 0, 0, false, isFile);
  }

  public boolean isInsideView(final String objectName) {
    return CCPathElement.isInsideView(objectName, getViewWholePath());
  }

  public String getRelativePath(final String path) {
    final List<CCPathElement> ccViewRootElements = CCPathElement.splitIntoPathElements(getClearCaseViewPath());
    final List<CCPathElement> viewPathElements = CCPathElement.splitIntoPathElements(getViewWholePath());
    final List<CCPathElement> pathElements = CCPathElement.splitIntoPathElements(path);

    if (pathElements.size() == 0 || ccViewRootElements.size() == 0)
      return ".";

    if (!pathElements.get(0).getPathElement().equals(ccViewRootElements.get(0).getPathElement())) {
      if ("".equals(pathElements.get(0).getPathElement())) {
        pathElements.remove(0);
      }
      for (int i = ccViewRootElements.size() - 1; i >= 0; i--) {
        pathElements.add(0, ccViewRootElements.get(i));
      }
    }

    final String result = CCPathElement.createPath(pathElements, viewPathElements.size(), pathElements.size(), true);
    return result.trim().length() == 0 ? "." : result;
  }

  public String getRelativePath(@NotNull final SimpleDirectoryChildElement simpleChild) {
    return getRelativePath(extractElementPath(simpleChild.getPathWithoutVersion()));
  }

  public String getClearCaseViewPath() {
    return myViewPath.getClearCaseViewPath();
  }

  public static InputStream getConfigSpecInputStream(final String viewPath) throws IOException {
    final InteractiveProcessFacade executor = getInteractiveProcess(viewPath);
    try {
      return executor.executeAndReturnProcessInput(new String[] { "catcs" });
    } catch (IOException e) {
      final String tag = getViewTag(viewPath);
      if (tag != null) {

        final InputStream stream = executor.executeAndReturnProcessInput(new String[] { "catcs", "-tag", tag });
        if (stream != null)
          return stream;
      }
      throw e;
    }
  }

  @Nullable
  public static String getViewTag(final String viewPath) throws IOException {
    final InputStream inputStream = getInteractiveProcess(viewPath).executeAndReturnProcessInput(new String[] { "lsview", "-cview" });
    final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    try {
      String line = reader.readLine().trim();
      if (line != null) {
        if (line.startsWith("*")) {
          line = line.substring(1).trim();
        }
        int spacePos = line.indexOf(' ');
        if (spacePos != -1) {
          line = line.substring(0, spacePos);
        }
      }
      return line;
    } finally {
      reader.close();
    }
  }

  public void processAllVersions(final String version, final VersionProcessor versionProcessor, boolean processRoot, boolean useCache) throws VcsException {
    if (useCache && myCache != null) {
      myCache.getCache(version, getViewWholePath(), IncludeRule.createDefaultInstance(), myRoot).processAllVersions(versionProcessor, processRoot, this);
    } else {
      final String directoryVersion = prepare(version).getWholeName();

      String dirPath = getViewWholePath() + CCParseUtil.CC_VERSION_SEPARATOR + directoryVersion;

      if (processRoot) {
        versionProcessor.processDirectory(dirPath, "", getViewWholePath(), directoryVersion, this);
      }

      try {
        processAllVersionsInternal(dirPath, versionProcessor, "./");
      } finally {
        if (processRoot) {
          versionProcessor.finishProcessingDirectory();
        }
      }
    }

  }

  public void processAllVersions(final String fullPath, String relPath, final VersionProcessor versionProcessor) throws VcsException {
    processAllVersionsInternal(fullPath, versionProcessor, relPath);

  }

  private void processAllVersionsInternal(final String dirPath, final VersionProcessor versionProcessor, String relativePath) throws VcsException {
    final List<DirectoryChildElement> subfiles = CCParseUtil.readDirectoryVersionContent(this, dirPath);

    for (DirectoryChildElement subfile : subfiles) {
      final String fileFullPath = CCPathElement.removeUnneededDots(subfile.getFullPath());
      String newRelPath = "./".equals(relativePath) ? CCParseUtil.getFileName(subfile.getPath()) : relativePath + File.separator + CCParseUtil.getFileName(subfile.getPath());
      String elemPath = getViewWholePath() + File.separator + newRelPath;
      if (subfile.getType() == DirectoryChildElement.Type.FILE) {
        final ClearCaseFileAttr fileAttr = loadFileAttr(subfile.getPathWithoutVersion() + CCParseUtil.CC_VERSION_SEPARATOR);
        versionProcessor.processFile(fileFullPath, newRelPath, elemPath, subfile.getStringVersion(), this, fileAttr.isIsText(), fileAttr.isIsExecutable());
      } else {
        versionProcessor.processDirectory(fileFullPath, newRelPath, elemPath, subfile.getStringVersion(), this);
        try {
          processAllVersionsInternal(fileFullPath, versionProcessor, newRelPath);
        } finally {
          versionProcessor.finishProcessingDirectory();
        }
      }
    }
  }

  public Version prepare(final String lastVersion) throws VcsException {
    collectChangesToIgnore(lastVersion);
    try {
      final Version viewLastVersion = findVersionFor(lastVersion);
      if (viewLastVersion == null) {
        throw new VcsException("Cannot get version in view '" + getViewWholePath() + "' for the directory " + getViewWholePath());
      }
      return viewLastVersion;

    } catch (Exception e) {
      throw new VcsException(e);
    }

  }

  private Version findVersionFor(String lastVersion) throws Exception {
    final File path = new File(getViewWholePath());
    final String[] versions = CTool.lsVTree(path);
    final Date onDate = CCParseUtil.getDateFormat().parse(lastVersion);
    for (int i = versions.length - 1; i >= 0; i--) {//reverse order
      final VersionParser parser = CTool.describe(path, versions[i]);
      if (parser.getCreationDate().before(onDate)) {
        final Version ccVersion = findVersion(path.getAbsolutePath(), parser.getVersion(), true);
        if (ccVersion != null && versionIsInsideView(path.getAbsolutePath(), ccVersion.getWholeName(), false)) {
          return ccVersion;
        }
      }
    }
    return getLastVersion(path.getAbsolutePath(), false);
  }

  public void mklabel(final String version, final String pname, final String label, final boolean isDirPath) throws VcsException, IOException {
    try {
      InputStream inputStream = executeAndReturnProcessInput(new String[] { "mklabel", "-replace", "-version", version, label, insertDots(pname, isDirPath) });
      try {
        inputStream.close();
      } catch (IOException e) {
        //ignore
      }
    } catch (IOException e) {
      if (!e.getLocalizedMessage().contains("already on element"))
        throw e;
    }
  }

  public ClearCaseFileAttr loadFileAttr(final String path) throws VcsException {
    try {
      final InputStream input = executeAndReturnProcessInput(new String[] { "describe", insertDots(cutOffVersion(path), false) });
      try {
        return ClearCaseFileAttr.readFrom(input);
      } finally {
        try {
          input.close();
        } catch (IOException e1) {
          //ignore
        }
      }
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  private String cutOffVersion(final String path) {
    final int versionSep = path.lastIndexOf(CCParseUtil.CC_VERSION_SEPARATOR);
    if (versionSep != -1) {
      return path.substring(0, versionSep + CCParseUtil.CC_VERSION_SEPARATOR.length());
    } else
      return path + CCParseUtil.CC_VERSION_SEPARATOR;
  }

  public boolean fileExistsInParents(@NotNull final HistoryElement element, final boolean isFile) throws VcsException {
    return doFileExistsInParents(new File(CCPathElement.normalizePath(element.getObjectName())), myViewPath.getWholePathFile(), isFile);
  }

  private boolean doFileExistsInParents(@NotNull final File objectFile, @NotNull final File viewFile, final boolean objectIsFile) throws VcsException {
    if (objectFile.equals(viewFile))
      return true;

    final File parentFile = objectFile.getParentFile();
    final String parentPath = parentFile.getAbsolutePath();
    final List<CCPathElement> elements = CCPathElement.splitIntoPathElements(parentPath);
    final CCPathElement lastElement = elements.get(elements.size() - 1);

    if (lastElement.getVersion() == null) {
      final Version version = getLastVersion(parentPath, false);
      if (version == null)
        return false;
      lastElement.setVersion(version.getWholeName());
    }

    final String parentPathWithVersion = CCPathElement.createPath(elements, elements.size(), true);

    if (hasChild(parentPathWithVersion, objectFile.getName(), objectIsFile)) {
      return doFileExistsInParents(parentFile, viewFile, false);
    }

    return false;
  }

  @NotNull
  public static String getClearCaseViewRoot(@NotNull final String viewPath) throws VcsException, IOException {
    final String normalPath = CCPathElement.normalizePath(viewPath);

    final InputStream inputStream = getInteractiveProcess(normalPath).executeAndReturnProcessInput(/*params)executeSimpleProcess(normalPath, */new String[] { "pwv", "-root" });
    final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

    try {
      final String viewRoot = reader.readLine();
      if (viewRoot == null || "".equals(viewRoot.trim())) {
        int offset = 0;
        if (normalPath.startsWith(UNIX_VIEW_PATH_PREFIX)) {
          offset = UNIX_VIEW_PATH_PREFIX.length();
        }
        final int sep = normalPath.indexOf(File.separatorChar, offset);
        if (sep == -1)
          return normalPath;
        return normalPath.substring(0, sep);
      }
      return viewRoot;
    } finally {
      reader.close();
    }
  }

  public static boolean isClearCaseView(@NotNull final String ccViewPath) throws IOException {
    try {
      final String normalPath = CCPathElement.normalizePath(ccViewPath);
      return normalPath.equalsIgnoreCase(getClearCaseViewRoot(normalPath));
    } catch (VcsException ignored) {
      return false;
    }
  }

  @NotNull
  private String insertDots(@NotNull final String fullPath, final boolean isDirPath) throws VcsException {
    final List<CCPathElement> filePath = CCPathElement.splitIntoPathElements(CCPathElement.normalizePath(fullPath));

    final int lastIndex = filePath.size() - (isDirPath ? 1 : 2);
    for (int i = lastIndex; i >= 0; i--) {
      final CCPathElement element = filePath.get(i);
      final String version = element.getVersion();
      if (version == null)
        continue;

      final CCPathElement dotElement = new CCPathElement(".", false, true);
      dotElement.setVersion(version);
      element.setVersion(null);

      filePath.add(i + 1, dotElement);
    }

    return CCPathElement.createPath(filePath, filePath.size(), true);
  }

  public String getPreviousVersion(final HistoryElement element, final boolean isDirPath) throws VcsException, IOException {
    String path = element.getObjectName().trim();
    if (path.endsWith(CCParseUtil.CC_VERSION_SEPARATOR)) {
      path = path + element.getObjectVersion();
    } else {
      path = path + CCParseUtil.CC_VERSION_SEPARATOR + element.getObjectVersion();
    }

    final InputStream inputStream = getInteractiveProcess(getViewWholePath()).executeAndReturnProcessInput(/*params)executeSimpleProcess(getViewWholePath(), */new String[] { "describe", "-s", "-pre", insertDots(path, isDirPath) });
    final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

    try {
      return reader.readLine();
    } finally {
      reader.close();
    }
  }

  @NotNull
  public List<SimpleDirectoryChildElement> getChildren(@NotNull final String dirPathWithVersion) throws VcsException {
    if (!myDirectoryContentCache.containsKey(dirPathWithVersion)) {
      myDirectoryContentCache.put(dirPathWithVersion, doGetChildren(dirPathWithVersion));
    }
    return myDirectoryContentCache.get(dirPathWithVersion);
  }

  @NotNull
  private List<SimpleDirectoryChildElement> doGetChildren(@NotNull final String dirPathWithVersion) throws VcsException {
    final List<SimpleDirectoryChildElement> subfiles = new ArrayList<SimpleDirectoryChildElement>();

    try {
      final InputStream inputStream = listDirectoryContent(dirPathWithVersion);
      final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          final SimpleDirectoryChildElement element = CCParseUtil.readChildFromLSFormat(line);
          if (element != null) {
            subfiles.add(element);
          }
        }
      } finally {
        reader.close();
      }
    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (IOException e) {
      throw new VcsException(e);
    }

    return subfiles;
  }

  public static boolean isLabelExists(@NotNull final String viewPath, @NotNull final String label) throws /*Vcs*/IOException {
    final InputStream inputStream = getInteractiveProcess(viewPath).executeAndReturnProcessInput(/*params)executeSimpleProcess(viewPath, */new String[] { "lstype", "-kind", "lbtype", "-short" });
    final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        if (line.equals(label)) {
          return true;
        }
      }
      return false;
    } finally {
      reader.close();
    }
  }

  public void processAllParents(@NotNull final String version, @NotNull final VersionProcessor versionProcessor, @NotNull final String path) throws VcsException {
    prepare(version);

    final File viewPathFile = myViewPath.getClearCaseViewPathFile();
    final File vobsPathFile = new File(viewPathFile, Constants.VOBS_NAME_ONLY);
    File currentPathFile = new File(path).getParentFile();

    while (currentPathFile != null && !currentPathFile.equals(viewPathFile) && !currentPathFile.equals(vobsPathFile)) {
      final String pname = currentPathFile.getAbsolutePath();
      final Version lastVersion = getLastVersion(pname, false);
      if (lastVersion == null) {
        final String message = "Cannot find last version for directory \"" + pname + "\"";
        LOG.error(message);
        throw new VcsException(message);
      }
      final String directoryVersion = lastVersion.getWholeName();
      final String fullPath = pname + CCParseUtil.CC_VERSION_SEPARATOR + directoryVersion;

      try {
        versionProcessor.processDirectory(fullPath, "", pname, directoryVersion, this);
      } finally {
        versionProcessor.finishProcessingDirectory();
      }

      currentPathFile = currentPathFile.getParentFile();
    }
  }

  private boolean hasChild(@NotNull final String parentPathWithVersion, @NotNull final String objectName, final boolean isFile) throws VcsException {
    final List<SimpleDirectoryChildElement> children = getChildren(parentPathWithVersion);
    for (final SimpleDirectoryChildElement child : children) {
      if (matches(child.getType(), isFile) && objectName.equals(child.getName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(@NotNull final DirectoryChildElement.Type type, final boolean isFile) {
    return (type == DirectoryChildElement.Type.FILE) == isFile;
  }

  public static class ClearCaseInteractiveProcess extends InteractiveProcess {
    private final Process myProcess;

    public ClearCaseInteractiveProcess(final Process process) {
      super(process.getInputStream(), process.getOutputStream());
      myProcess = process;
    }

    @Override
    protected void execute(final String[] args) throws IOException {
      super.execute(args);
      final StringBuffer commandLine = new StringBuffer();
      commandLine.append("cleartool");
      for (String arg : args) {
        commandLine.append(' ');
        if (arg.contains(" ")) {
          commandLine.append("'").append(arg).append("'");
        } else {
          commandLine.append(arg);
        }

      }
      LOG.debug("interactive execute: " + commandLine.toString());
    }

    @Override
    protected boolean isEndOfCommandOutput(final String line, final String[] params) throws IOException {
      if (line.startsWith("Command ")) {
        final String restStr = " returned status ";
        final int statusPos = line.indexOf(restStr);
        if (statusPos != -1) {
          //get return status
          final String retCode = line.substring(statusPos + restStr.length(), line.length()).trim();
          if (!"0".equals(retCode)) {
            String error = readError();
            throw new IOException(new StringBuilder("Error executing ").append(Arrays.toString(params)).append(": ").append(error).toString());
          }
          return true;
        }
      }
      return false;
    }

    @Override
    protected void lineRead(final String line) {
      super.lineRead(line);
      if (LOG.isDebugEnabled()) {
        LOG.debug("output line read: " + line);
      }
    }

    @Override
    protected InputStream getErrorStream() {
      return myProcess.getErrorStream();
    }

    @Override
    protected void destroyOSProcess() {
      myProcess.destroy();
    }

    @Override
    protected void executeQuitCommand() throws IOException {
      super.executeQuitCommand();
      execute(new String[] { "quit" });
    }

    public void copyFileContentTo(final ClearCaseConnection connection, final String version, final File destFile) throws IOException, VcsException {
      final InputStream input = executeAndReturnProcessInput(new String[] { "get", "-to", connection.insertDots(destFile.getAbsolutePath(), false), connection.insertDots(version, false) });
      input.close();
    }
  }

  /**
   * just for testing purpose
   */
  public static void cleanup() {
    synchronized (ourViewProcesses) {
      for (Map.Entry<String, InteractiveProcessFacade> entry : ourViewProcesses.entrySet()) {
        entry.getValue().destroy();
        System.out.println(String.format("Interactive process for '%s' disposed", entry.getKey()));
      }
      ourViewProcesses.clear();
    }
  }
}
