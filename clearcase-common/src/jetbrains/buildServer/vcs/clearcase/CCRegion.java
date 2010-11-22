/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package jetbrains.buildServer.vcs.clearcase;

import java.io.File;
import java.util.ArrayList;

import jetbrains.buildServer.vcs.clearcase.CTool.StorageParser;
import jetbrains.buildServer.vcs.clearcase.CTool.ViewParser;
import jetbrains.buildServer.vcs.clearcase.CTool.VobParser;

public class CCRegion {

  private String myTag;

  public CCRegion() {
    this("any");
  }

  public CCRegion(final String tag) {
    myTag = tag;
  }

  public String getTag() {
    return myTag;
  }

  public CCVob[] getVobs() throws CCException {
    try {
      final ArrayList<CCVob> out = new ArrayList<CCVob>();
      for (VobParser result : CTool.lsVob()) {
        out.add(new CCVob(result.getTag(), result.getRegion(), result.getServerHost(), result.getGlobalPath()));
      }
      return out.toArray(new CCVob[out.size()]);

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public CCStorage[] getStorages() throws CCException {
    try {
      final ArrayList<CCStorage> out = new ArrayList<CCStorage>();
      for (StorageParser result : CTool.lsStgLoc()) {
        out.add(new CCStorage(result.getServerHost(), result.getType(), result.getTag(), result.getGlobalPath()));
      }
      return out.toArray(new CCStorage[out.size()]);

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public CCSnapshotView[] getViews() throws CCException {
    try {
      final ArrayList<CCSnapshotView> out = new ArrayList<CCSnapshotView>();
      for (ViewParser result : CTool.lsView()) {
        out.add(new CCSnapshotView(result.getRegion(), result.getServerHost(), result.getTag(), new File(result.getGlobalPath()), result.getAttributes().contains(ViewParser.ATTRIBUTE_UCM)));
      }
      return out.toArray(new CCSnapshotView[out.size()]);

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

}
