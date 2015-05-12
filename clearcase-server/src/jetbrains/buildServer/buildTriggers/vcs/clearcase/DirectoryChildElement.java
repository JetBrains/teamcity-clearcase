/*
 * Copyright 2000-2015 JetBrains s.r.o.
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

import org.jetbrains.annotations.NotNull;

public class DirectoryChildElement extends SimpleDirectoryChildElement {
  private final @NotNull String myPath;
  private final int myVersion;
  private final @NotNull String myFullPath;
  private final @NotNull String myStringVersion;

  public DirectoryChildElement(@NotNull final Type type,
                               @NotNull final String path,
                               final int version,
                               @NotNull final String fullPath,
                               @NotNull final String stringVersion,
                               @NotNull final String pathWithoutVersion) {
    super(pathWithoutVersion, type);
    myPath = path;
    myVersion = version;
    myFullPath = fullPath;
    myStringVersion = stringVersion;
  }

  @NotNull
  public String getPath() {
    return myPath;
  }

  public int getVersion() {
    return myVersion;
  }

  @NotNull
  public String getFullPath() {
    return myFullPath;
  }

  @NotNull
  public String getStringVersion() {
    return myStringVersion;
  }

  public String toString() {
    return myPath;
  }
}
