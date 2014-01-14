/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.clearcase.structure;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.*;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import org.apache.log4j.Logger;

public class CacheElement {
  private static final Logger LOG = Logger.getLogger(CacheElement.class);

  private final DateRevision myVersion;
  private final File myCacheFile;
  private final ClearCaseStructureCache myOwner;
  private final String myPath;
  public static final int FILE_TYPE = 0;
  public static final int DIR_OPEN_TYPE = 1;
  public static final int DIR_CLOSE_TYPE = 2;
  
  private final ClearCaseSupport myParentSupport;
  private final VcsRoot myRoot;
  private final IncludeRule myIncludeRule;

  public CacheElement(final DateRevision version,
                      final File cacheFile,
                      ClearCaseStructureCache owner,
                      final String path,
                      final IncludeRule includeRule,
                      final ClearCaseSupport parentSupport,
                      final VcsRoot root) {
    myVersion = version;
    myCacheFile = cacheFile;
    myOwner = owner;
    myPath = path;
    myIncludeRule = includeRule;
    myParentSupport = parentSupport;
    myRoot = root;

    LOG.debug("ClearCase cache " + cacheFile.getPath() + " created for " + path);
  }

  public void processAllVersions(final VersionProcessor versionProcessor, boolean processRoot, ClearCaseConnection connection) throws VcsException {
    try {
      if (!myCacheFile.exists()) {
        CacheElement nearestCache = myOwner.getNearestExistingCache(myVersion, myPath, myIncludeRule, myRoot);
        if (nearestCache == null) {
          LOG.debug("ClearCase cache " + myCacheFile.getPath() + " loading all versions");
          loadAllRevisions(myVersion, connection);
        }
        else {
          LOG.debug("ClearCase cache " + myCacheFile.getPath() + " loading differences from " + nearestCache.getVersion().asString());
          loadDifferences(nearestCache, connection);
        }
      }

      processAllVersionsInternal(versionProcessor, processRoot, connection);
    }
    catch (final IOException e) {
      connection.processAllVersions(myVersion, versionProcessor, processRoot, false);
    }
  }

  private void loadAllRevisions(DateRevision version, ClearCaseConnection connection) throws IOException {
    //noinspection ResultOfMethodCallIgnored
    myCacheFile.getParentFile().mkdirs();
    final DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(myCacheFile));
    try {
      connection.processAllVersions(version, new WriteVersionProcessor(outputStream), true, false);
      outputStream.close();
    } catch (Throwable e) {
      outputStream.close();
      FileUtil.delete(myCacheFile);
    }

  }

  private void loadDifferences(final CacheElement nearestCache, final ClearCaseConnection connection) throws IOException, VcsException {
    final List<ChangedElementInfo> changedElements = loadChanges(nearestCache);
    
    final DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(myCacheFile));
    final WriteVersionProcessor writeProcessor = new WriteVersionProcessor(outputStream);
    try {
      new CacheProcessor(new WriteCorrectingVersionProcessor(changedElements, writeProcessor), connection, nearestCache.getCacheFile())
        .processAllRevisions(true);
    } finally {
      outputStream.close();
    }
  }

  public File getCacheFile() {
    return myCacheFile;
  }

  private List<ChangedElementInfo> loadChanges(final CacheElement nearestCache) throws IOException, VcsException {
/*
    if (myParentSupport.isViewPathIsExactlyCCViewPath(myRoot, myIncludeRule)) {
      final List<ConfigSpecLoadRule> loadRules = ConfigSpecParseUtil.getConfigSpec(ClearCaseSupport.getViewPath(myRoot)).getLoadRules();
      if (loadRules.isEmpty()) {
        throw new VcsException("There is no neither 'relative path' setting nor checkout rules nor config spec load rules");
      }
      Set<ChangedElementInfo> set = new HashSet<ChangedElementInfo>();
      for (ConfigSpecLoadRule loadRule : loadRules) {
         set.addAll(loadChangesWithConnection(nearestCache, myParentSupport.createConnection(myRoot, myIncludeRule, loadRule)));
      }
      return Collections.list(Collections.enumeration(set));
    }
    else {
*/
    final ClearCaseConnection connection = myParentSupport.createConnection(myRoot, myIncludeRule, null);
    try {
      return loadChangesWithConnection(nearestCache, connection);
    }
    finally {
      connection.dispose();
    }
//    }
  }

  private List<ChangedElementInfo> loadChangesWithConnection(CacheElement nearestCache, ClearCaseConnection tempConnection) throws VcsException, IOException {
    final CollectingChangedFilesProcessor processor = new CollectingChangedFilesProcessor(tempConnection);
    CCParseUtil.processChangedFiles(tempConnection, nearestCache.getVersion(), myVersion, processor);
    return processor.getChanges();
  }

  public DateRevision getVersion() {
    return myVersion;
  }

  private void processAllVersionsInternal(final VersionProcessor versionProcessor,
                                          final boolean processRoot,
                                          final ClearCaseConnection connection) throws VcsException, IOException {
    new CacheProcessor(versionProcessor, connection, myCacheFile).processAllRevisions(processRoot);
  }

}
