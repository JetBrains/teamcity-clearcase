/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.CCPathElement;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Branch;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Version;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.VersionTree;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Util;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ConfigSpecStandardRule {
  @NonNls @NotNull private static final String ELLIPSIS = "...";
  private final ScopeType myScopeType;
  protected final Pattern myScopePattern;
  protected final Pattern myBranchPattern;
  protected final String myVersion;
  private final String myMkBranchOption;
  private final boolean myIsLabelSelector;
  @Nullable private String myPrimaryBranch;

  public ResultType isVersionIsInsideView(final Version version) {
    final String versionFullName = version.getWholeName();
    int lastSepPos = versionFullName.lastIndexOf(File.separatorChar);
    final String branch = lastSepPos == -1 ? "" : versionFullName.substring(0, lastSepPos);

    if (!myBranchPattern.matcher(branch).matches()) return ResultType.DOES_NOT_MATCH;

    final String versionNumber = versionFullName.substring(lastSepPos + 1);
    ResultType result;

    if (Util.isDigit(myVersion)/*StringUtil.isNumber(myVersion)*/) {
      result = myVersion.equals(versionNumber) ? ResultType.MATCHES : ResultType.DOES_NOT_MATCH;
    }
    else {
      if (ConfigSpecRuleTokens.CHECKEDOUT.equalsIgnoreCase(myVersion)) {
        result = ResultType.DOES_NOT_MATCH; //todo
      } else if (ConfigSpecRuleTokens.LATEST.equalsIgnoreCase(myVersion)) {
        result = ResultType.MATCHES;
      } else { // label
        result = version.containsComment(myVersion) ? ResultType.MATCHES : ResultType.DOES_NOT_MATCH;
      }
    }

    if (ResultType.DOES_NOT_MATCH.equals(result)) return ResultType.DOES_NOT_MATCH;
    if (myMkBranchOption == null) return ResultType.MATCHES;

    return makeBranch(version);
  }

  private ResultType makeBranch(final Version version) {
    if (version.getInheritedBranchByName(myMkBranchOption) != null) return ResultType.BRANCH_HAS_NOT_BEEN_MADE;

    final Branch branch = new Branch(version, myMkBranchOption);
    branch.addVersion(0, new ArrayList<String>());
    version.addInheritedBranch(branch);

    return ResultType.BRANCH_HAS_BEEN_MADE;
  }

  @Nullable
  public Version findVersion(final VersionTree versionTree, final String fullFileName) throws VcsException {
    final Collection<Branch> branches = findBranches(versionTree);
    if (branches == null) {
      return null;
    }

    final Version version = processBranches(fullFileName, branches);
    if (version == null) return null;

//    return myMkBranchOption == null ? version : makeBranch(connection, myMkBranchOption, fullFileName, version);
    return version;
  }

  /*
  private Version makeBranch(final ClearCaseConnection connection,
                             final String branchName,
                             final String fullFileName,
                             final Version version) throws VcsException, IOException {
    if (connection != null) {
      connection.makeBranchForFile(fullFileName, version.getWholeName(), branchName);
    }
    final Branch branch = new Branch(version, branchName);
    branch.addVersion(0, new ArrayList<String>());
    version.addInheritedBranch(branch);
    return Version.createInvalidVersion();
  }
  */

  @Nullable
  private Version processBranches(final String fullFileName, final Collection<Branch> branches) throws VcsException {
    if (Util.isDigit(myVersion)/*StringUtil.isNumber(myVersion)*/) {
      int versionNumber = Integer.parseInt(myVersion);
      return findVersionByNumber(branches, versionNumber, fullFileName);
    }
    else {
      if (ConfigSpecRuleTokens.CHECKEDOUT.equalsIgnoreCase(myVersion)) {
        return null; //todo
      } else if (ConfigSpecRuleTokens.LATEST.equalsIgnoreCase(myVersion)) {
        return getLastVersion(branches, fullFileName);
      } else { // label
        return findVersionWithComment(branches, myVersion, fullFileName);
      }
    }
  }

  @Nullable
  private Version findVersionWithComment(final Collection<Branch> branches, final String labelName, final String fullFileName) throws VcsException {
    Collection<Version> versions = new HashSet<Version>();
    for (Branch branch : branches) {
      Version version = branch.findVersionWithComment(labelName, false);
      if (version != null) {
        versions.add(version);
      }
    }
    return processVersions(versions, fullFileName);
  }

  @Nullable
  private Version getLastVersion(final Collection<Branch> branches, final String fullFileName) throws VcsException {
    Collection<Version> versions = new HashSet<Version>();
    for (Branch branch : branches) {
      versions.add(branch.getLastVersion());
    }
    return processVersions(versions, fullFileName);
  }

  @Nullable
  private Version findVersionByNumber(final Collection<Branch> branches, final int versionNumber, final String fullFileName) throws VcsException {
    Collection<Version> versions = new HashSet<Version>();
    for (Branch branch : branches) {
      Version version = branch.findVersionByNumber(versionNumber);
      if (version != null) {
        versions.add(version);
      }
    }
    return processVersions(versions, fullFileName);
  }

  @Nullable
  private Version processVersions(final Collection<Version> versions, final String fullFileName) throws VcsException {
    if (versions.size() == 0) {
      return null;
    }
    if (versions.size() > 1) {
      throw new VcsException("Version of \"" + fullFileName + "\" is ambiguous: " + collectVersions(versions) + ".");
    }
    return versions.iterator().next();
  }

  private String collectVersions(final Collection<Version> versions) {
    StringBuilder sb = new StringBuilder("");
    for (Version version : versions) {
      sb.append(version.getWholeName()).append("; ");
    }
    if (sb.length() > 1) {
      return sb.substring(0, sb.length() - 2);
    }
    return sb.toString();
  }

  @Nullable
  private Collection<Branch> findBranches(final VersionTree versionTree) {
    Map<String, Branch> branches = versionTree.getAllBranchesWithFullNames();
    Collection<Branch> result = new ArrayList<Branch>();
    for (Map.Entry<String, Branch> entry : branches.entrySet()) {
      if (myBranchPattern.matcher(entry.getKey()).matches()) {
        result.add(entry.getValue());
      }
    }
    return result;
  }

  public ConfigSpecStandardRule(final String scope, final String scopePattern, final String versionSelectorWithOptions) {
    String scopeTypeName = scope.substring(0, scope.indexOf(':'));
    if (ConfigSpecRuleTokens.STANDARD_FILE.equalsIgnoreCase(scopeTypeName)) {
      myScopeType = ScopeType.FILE;
    } else if (ConfigSpecRuleTokens.STANDARD_DIRECTORY.equalsIgnoreCase(scopeTypeName)) {
      myScopeType = ScopeType.DIRECTORY;
    } else if (ConfigSpecRuleTokens.STANDARD_ELTYPE.equalsIgnoreCase(scopeTypeName)) {
      myScopeType = ScopeType.ANY; //todo
    } else {
      myScopeType = ScopeType.ANY;
    }
    myScopePattern = createPattern(removeFirstSeparatorIfNeeded(scopePattern.trim()), false);
    if (versionSelectorWithOptions.startsWith("{")) {
      //todo
    }
    final String versionSelector = ConfigSpecParseUtil.extractFirstWord(versionSelectorWithOptions);
    myMkBranchOption = getMkBranchOption(versionSelectorWithOptions.substring(versionSelector.length()).trim());
    final String normalizedVersionSelector = CCPathElement.normalizeSeparators(versionSelector.trim());
    int lastSeparatorPos = normalizedVersionSelector.lastIndexOf(File.separatorChar);
    if (lastSeparatorPos == -1) {
      myBranchPattern = Pattern.compile(".*");
    }
    else {
      final String branchPathSelector = normalizedVersionSelector.substring(0, lastSeparatorPos);
      myBranchPattern = createPattern(branchPathSelector, true);
      detectPrimaryBranch(branchPathSelector);
    }
    myVersion = normalizedVersionSelector.substring(lastSeparatorPos + 1);
    if (myVersion.startsWith("{")) {
      //todo
    }
    myIsLabelSelector = isLabelBasedSelector();
  }

  // todo maybe set primary branch to null for rule "/main/0"? or for any rule with version == "0"?
  private void detectPrimaryBranch(@NotNull final String branchPathSelector) {
    final String[] branches = branchPathSelector.split(escapeBackSlash(File.separator));
    if (branches.length > 0) {
      final String primaryBranchCandidate = branches[branches.length - 1];
      if (!StringUtil.isEmptyOrSpaces(primaryBranchCandidate) && !ELLIPSIS.equals(primaryBranchCandidate)) {
        myPrimaryBranch = primaryBranchCandidate;
      }
    }
  }

  private boolean isLabelBasedSelector() {
    return !Util.isDigit(myVersion)/*StringUtil.isNumber(myVersion)*/
           && !ConfigSpecRuleTokens.CHECKEDOUT.equalsIgnoreCase(myVersion)
           && !ConfigSpecRuleTokens.LATEST.equalsIgnoreCase(myVersion);
  }

  private String removeFirstSeparatorIfNeeded(@NotNull final String scopePattern) {
    if (File.separatorChar == '\\' && (scopePattern.startsWith("\\") || scopePattern.startsWith("/"))) {
      return scopePattern.substring(1);
    }
    return scopePattern;
  }

  @Nullable
  private String getMkBranchOption(final String s) {
    boolean wordShouldBeReturned = false;
    String ss = s;
    while (ss.length() > 0) {
      final String word = ConfigSpecParseUtil.extractFirstWord(ss), trimmedWord = word.trim();
      if (wordShouldBeReturned) return trimmedWord;
      ss = ss.substring(word.length()).trim();
      if (ConfigSpecRuleTokens.MKBRANCH_OPTION.equalsIgnoreCase(trimmedWord)) wordShouldBeReturned = true;      
    }
    return null;
  }

  private static Pattern createPattern(final String pattern, final boolean isVersionPattern) {
    return Pattern.compile(createCommonPattern(escapeBackSlash(CCPathElement.normalizeSeparators(pattern)), isVersionPattern));
  }

  private static String escapeBackSlash(final String s) {
    return s.replace("\\","\\\\");
  }

  private static String createCommonPattern(final String pattern, final boolean isVersionPattern) {
    String result = pattern;
    result = escapeDots(result);
    result = result.replace("*", ".*");
    result = result.replace("?", ".");

    String escapedSeparator = escapeBackSlash(File.separator);

    final String s = isVersionPattern ? escapedSeparator : "/";
    if (!result.startsWith(s) && !result.startsWith(".*")) {
      result = "(.*" + escapedSeparator + ")?" + result;
    }

    result = result.replace(escapedSeparator + ELLIPSIS, "(" + escapedSeparator + "[^" + escapedSeparator + "]+)*");
    result = result.replace(ELLIPSIS + escapedSeparator, "([^" + escapedSeparator + "]+" + escapedSeparator + ")*");

    return result;
  }

  private static String escapeDots(final String string) {
    StringBuilder sb = new StringBuilder();
    int i = 0, len = string.length();
    while (i < len) {
      if (string.charAt(i) == '.') {
        if (i + 2 >= len || string.charAt(i + 1) != '.' || string.charAt(i + 2) != '.') {
          sb.append("\\.");
          i++;
        }
        else {
          sb.append(ELLIPSIS);
          i += 3;
        }
      }
      else {
        sb.append(string.charAt(i));
        i++;
      }
    }
    return sb.toString();
  }

  public boolean matchesPath(String fullFilePath, final boolean isFile) {
    return myScopeType.matches(isFile) && myScopePattern.matcher(fullFilePath).matches();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof ConfigSpecStandardRule)) return false;

    final ConfigSpecStandardRule that = (ConfigSpecStandardRule)o;

    if (!myBranchPattern.pattern().equals(that.myBranchPattern.pattern())) return false;
    if (!myScopePattern.pattern().equals(that.myScopePattern.pattern())) return false;
    if (myScopeType != that.myScopeType) return false;
    if (!myVersion.equals(that.myVersion)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myScopeType.hashCode();
    result = 31 * result + myScopePattern.pattern().hashCode();
    result = 31 * result + myBranchPattern.pattern().hashCode();
    result = 31 * result + myVersion.hashCode();
    return result;
  }

  enum ScopeType {
    ANY(true, true),
    FILE(true, false),
    DIRECTORY(false, true);

    private final boolean myCanBeFile;

    private final boolean myCanBeDirectory;

    ScopeType(final boolean canBeFile, final boolean canBeDirectory) {
      myCanBeFile = canBeFile;
      myCanBeDirectory = canBeDirectory;
    }

    public boolean matches(final boolean file) {
      return file ? myCanBeFile : myCanBeDirectory;
    }

  }

  public enum ResultType {
    MATCHES,
    DOES_NOT_MATCH,
    BRANCH_HAS_BEEN_MADE,
    BRANCH_HAS_NOT_BEEN_MADE
  }

  /**
   * @return true if Version Selector refers to a Label 
   */
	public boolean isLabelBasedVersionSelector() {
		return myIsLabelSelector;
	}

  @Nullable
  public String getPrimaryBranch() {
    return myPrimaryBranch;
  }
}
