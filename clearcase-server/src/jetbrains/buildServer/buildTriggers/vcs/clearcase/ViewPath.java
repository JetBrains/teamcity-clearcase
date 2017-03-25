/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
import jetbrains.buildServer.vcs.FileRule;
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author maxim.manuylov
 */
public class ViewPath {
  private final String myCCViewPath;
  private final String myRelativePath;

  private String myIncludeRuleFrom;
  private String myWholePath;

  public ViewPath(@NotNull final String ccViewPath, @Nullable final String relativePath) throws VcsException {
    myCCViewPath = CCPathElement.normalizePath(ccViewPath.trim());
    myRelativePath = removeFirstSeparatorIfNeeded(CCPathElement.normalizePath(relativePath == null ? "" : relativePath.trim()));
    myIncludeRuleFrom = null;
    updateWholePath();
  }

  @NotNull
  private String removeFirstSeparatorIfNeeded(@NotNull final String path) {
    return path.startsWith(File.separator) && path.length() > 1 ? path.substring(1) : path;
  }

  @NotNull
  public String getClearCaseViewPath() {
    return myCCViewPath;
  }

  @NotNull
  public String getRelativePathWithinTheView() {
    return myRelativePath;
  }

  @NotNull
  public String getWholePath() {
    return myWholePath;
  }

  @NotNull
  public File getClearCaseViewPathFile() {
    return new File(myCCViewPath);
  }

  @NotNull
  public File getWholePathFile() {
    return new File(myWholePath);
  }

  public void setIncludeRuleFrom(@Nullable final FileRule includeRule) throws VcsException {
    if (includeRule == null) {
      myIncludeRuleFrom = null;
    } else {
      myIncludeRuleFrom = removeFirstSeparatorIfNeeded(CCPathElement.normalizeSeparators(includeRule.getFrom().trim()));
    }

    updateWholePath();
  }

  private void updateWholePath() throws VcsException {
    final StringBuilder sb = new StringBuilder(myCCViewPath);

    appendPath(sb, myRelativePath);
    appendPath(sb, myIncludeRuleFrom);

    myWholePath = CCPathElement.normalizePath(sb.toString());
  }

  private void appendPath(@NotNull final StringBuilder sb, @Nullable final String additionalPath) {
    if (additionalPath != null && !"".equals(additionalPath)) {
      sb.append(File.separatorChar);
      sb.append(additionalPath);
    }
  }

  @Override
  public int hashCode() {
    return getWholePath().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof ViewPath) {
      return getWholePath().equals(((ViewPath) obj).getWholePath());
    }
    return super.equals(obj);
  }
}
