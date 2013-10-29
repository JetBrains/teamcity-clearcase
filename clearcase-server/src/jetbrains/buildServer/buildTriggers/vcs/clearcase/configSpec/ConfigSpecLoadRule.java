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
import jetbrains.buildServer.util.FileUtil;

public class ConfigSpecLoadRule {
  private final File myFile;
  private final String myRelativePath;

  public ConfigSpecLoadRule(final File myViewRoot, final String path) {
    myFile = new File(myViewRoot, path);
    myRelativePath = path;
  }

  public String getRelativePath() {
    return myRelativePath;
  }

  public boolean isUnderLoadRule(final String elementPath) {
    final File elementFile = new File(elementPath);
    return FileUtil.isAncestor(elementFile, myFile, false) || FileUtil.isAncestor(myFile, elementFile, false);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof ConfigSpecLoadRule)) return false;

    final ConfigSpecLoadRule that = (ConfigSpecLoadRule)o;

    return myFile.equals(that.myFile);
  }

  @Override
  public int hashCode() {
    return myFile.hashCode();
  }
}
