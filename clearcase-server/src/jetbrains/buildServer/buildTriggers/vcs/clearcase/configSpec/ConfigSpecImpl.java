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

package jetbrains.buildServer.buildTriggers.vcs.clearcase.configSpec;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.CCPathElement;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseConnection;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ViewPath;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Branch;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Version;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.VersionTree;
import jetbrains.buildServer.vcs.VcsException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ConfigSpecImpl implements ConfigSpec {
  private final List<ConfigSpecLoadRule> myLoadRules;
  private final List<ConfigSpecStandardRule> myStandardRules;
  private boolean myViewIsDynamic;

  private static final Logger LOG = Logger.getLogger(ConfigSpecImpl.class);

  public ConfigSpecImpl(final List<ConfigSpecLoadRule> loadRules, final List<ConfigSpecStandardRule> standardRules) {
    myLoadRules = loadRules;
    myStandardRules = standardRules;
  }

  @Nullable
  public Version getCurrentVersion(final String ccViewRoot, final String fullFileName, final VersionTree versionTree, final boolean isFile) throws VcsException {
    final String normalizedFullFileName = CCPathElement.normalizeFileName(fullFileName);
    final Version version = doGetCurrentVersion(ccViewRoot, normalizedFullFileName, versionTree, isFile);

    if (version == null) {
      LOG.debug("ClearCase: element \"" + fullFileName + "\" ignored, last version not found;");
    }

    return version;
  }

  public boolean isVersionIsInsideView(final ClearCaseConnection connection, final List<CCPathElement> pathElements, final boolean isFile) throws VcsException, IOException {
    StringBuilder filePath = new StringBuilder("");
    StringBuilder objectPath = new StringBuilder("");

    for (int i = 0; i < pathElements.size(); i++) {
      CCPathElement pathElement = pathElements.get(i);
      filePath.append(File.separatorChar).append(pathElement.getPathElement());
      objectPath.append(File.separatorChar).append(pathElement.getPathElement());

      final String pathElementVersion = pathElement.getVersion();
      if (pathElementVersion != null) {
        final boolean elementIsFile = i == pathElements.size() - 1 && isFile;
        final Version version = connection.findVersion(CCPathElement.removeFirstSeparatorIfNeeded(objectPath), pathElementVersion, !elementIsFile);
        if (version == null) return false;
        objectPath.append(pathElementVersion);
        if (!doIsVersionIsInsideView(connection, CCPathElement.removeFirstSeparatorIfNeeded(filePath), version, elementIsFile)) {
          return false;
        }
      }
    }

    return true;
  }

  @NotNull
  public List<ConfigSpecLoadRule> getLoadRules() {
    return myLoadRules;
  }
  
  private boolean doIsVersionIsInsideView(final ClearCaseConnection connection, final String fullFileName, final Version version, final boolean isFile) throws VcsException {
    final String normalizedFullFileName = CCPathElement.normalizeFileName(fullFileName);
    if (!isUnderLoadRules(connection.getClearCaseViewPath(), normalizedFullFileName)) return false;

    final Version version_copy = new Version(version);

    boolean versionTreeHasBeenChanged;
    do {
      versionTreeHasBeenChanged = false;
      for (ConfigSpecStandardRule rule : myStandardRules) {
        if (!rule.matchesPath(normalizedFullFileName, isFile)) continue;
        final ConfigSpecStandardRule.ResultType result = rule.isVersionIsInsideView(version_copy);
        if (ConfigSpecStandardRule.ResultType.DOES_NOT_MATCH.equals(result)) {
          if (rightVersionExists(rule, getRootBranch(version_copy))) {
            return false;
          }
        }
        else if (ConfigSpecStandardRule.ResultType.BRANCH_HAS_BEEN_MADE.equals(result)) {
          versionTreeHasBeenChanged = true;
          break;
        }
        else if (ConfigSpecStandardRule.ResultType.MATCHES.equals(result)) {
          return true;
        }
      }
    } while (versionTreeHasBeenChanged);

    return false;
  }

  private Branch getRootBranch(final Version version) {
    Branch branch = version.getParentBranch();
    Version parentVersion = branch.getParentVersion();
    while (parentVersion != null) {
      branch = parentVersion.getParentBranch();
      parentVersion = branch.getParentVersion();
    }
    return branch;
  }

  private boolean rightVersionExists(final ConfigSpecStandardRule rule, final Branch rootBranch) {
    final Stack<Branch> stack = new Stack<Branch>();
    stack.push(rootBranch);

    while (!stack.isEmpty()) {
      final Branch branch = stack.pop();
      Version version = branch.getFirstVersion();
      while (version != null) {
        if (ConfigSpecStandardRule.ResultType.MATCHES.equals(rule.isVersionIsInsideView(version))) {
          return true;
        }
        stack.addAll(version.getInheritedBranches());
        version = version.getNextVersion();
      }
    }

    return false;
  }

  @Nullable
  private Version doGetCurrentVersion(final String ccViewRoot, final String fullFileName, final VersionTree versionTree, final boolean isFile) throws VcsException {
    if (!isUnderLoadRules(ccViewRoot, fullFileName)) {
      return null;
    }

    for (ConfigSpecStandardRule standardRule : myStandardRules) {
      if (standardRule.matchesPath(fullFileName, isFile)) {
        final Version version = standardRule.findVersion(versionTree, fullFileName);
        if (version != null) {
          return version;
        }
      }
    }

    return null;
  }

  public boolean isUnderLoadRules(final String ccViewRoot, final String fullFileName) throws VcsException {
    return myViewIsDynamic || doIsUnderLoadRules(fullFileName) ||
           doIsUnderLoadRules((new ViewPath(ccViewRoot, fullFileName)).getWholePath());
  }

  public void setViewIsDynamic(final boolean viewIsDynamic) {
    myViewIsDynamic = viewIsDynamic;
  }

  private boolean doIsUnderLoadRules(final String fullFileName) {
    for (ConfigSpecLoadRule loadRule : myLoadRules) {
      if (loadRule.isUnderLoadRule(fullFileName)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof ConfigSpecImpl)) return false;

    final ConfigSpecImpl that = (ConfigSpecImpl)o;

    return myLoadRules.equals(that.myLoadRules) && myStandardRules.equals(that.myStandardRules);
  }

  @Override
  public int hashCode() {
    int result = myLoadRules.hashCode();
    result = 31 * result + myStandardRules.hashCode();
    return result;
  }

  public boolean hasLabelBasedVersionSelector() {
    for (final ConfigSpecStandardRule rule : myStandardRules) {
      if (rule.isLabelBasedVersionSelector()) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  public SortedSet<String> getBranches() {
    final SortedSet<String> branches = new TreeSet<String>();
    for (final ConfigSpecStandardRule rule : myStandardRules) {
      final String primaryBranch = rule.getPrimaryBranch();
      if (primaryBranch != null) {
        branches.add(primaryBranch);
      }
    }
    return branches;
  }
}
