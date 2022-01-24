/*
 * Copyright 2000-2022 JetBrains s.r.o.
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
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SimpleDirectoryChildElement {
  @NotNull private final String myPathWithoutVersion;
  @NotNull private final Type myType;

  public SimpleDirectoryChildElement(@NotNull final String pathWithoutVersion, @NotNull final Type type) {
    myPathWithoutVersion = pathWithoutVersion;
    myType = type;
  }

  @NotNull
  public String getPathWithoutVersion() {
    return myPathWithoutVersion;
  }

  @NotNull
  public String getName() {
    return new File(myPathWithoutVersion).getName();
  }

  @NotNull
  public Type getType() {
    return myType;
  }

  @Nullable
  public DirectoryChildElement createFullElement(@NotNull final ClearCaseConnection connection) throws VcsException {
    return connection.getLastVersionElement(myPathWithoutVersion, myType);
  }

  public enum Type {
    FILE, DIRECTORY
  }
}
