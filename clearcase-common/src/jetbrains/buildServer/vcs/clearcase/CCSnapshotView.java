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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jetbrains.buildServer.vcs.clearcase.CTool.ChangeParser;
import jetbrains.buildServer.vcs.clearcase.CTool.ViewParser;
import jetbrains.buildServer.vcs.clearcase.CTool.VobObjectParser;

import org.apache.log4j.Logger;

public class CCSnapshotView {

  private static final Logger LOG = Logger.getLogger(CTool.class);

  private ArrayList<String> myConfigSpecs = new ArrayList<String>();
  private File myLocalPath;
  private String myGlobalPath;
  private String myTag;

  /**
   * @param localRoot
   *          folder where a CC view root is
   * @return
   * @throws CCException
   *           if the localRoot is not under CC View
   */
  public static CCSnapshotView init(File localRoot) throws CCException {
    try {
      final ViewParser parser = CTool.lsView(localRoot);
      final CCSnapshotView view = new CCSnapshotView(parser.getRegion(), parser.getServerHost(), parser.getTag(), parser.getGlobalPath());
      view.myLocalPath = localRoot;
      return view;
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  CCSnapshotView(final String region, final String server, final String tag, final String glolbalPath) {
    myTag = tag;
    myGlobalPath = glolbalPath;
  }

  public CCSnapshotView(final String tag, final String localPath) {
    myTag = tag;
    myLocalPath = new File(localPath);
  }

  public CCSnapshotView(final CCVob vob, final String tag, final String localPath) {
    myTag = tag;
    myConfigSpecs = new ArrayList<String>(CTool.DEFAULT_CONFIG_SPECS);
    myConfigSpecs.add(Util.createLoadRuleForVob(vob));
    myLocalPath = new File(localPath);
    myLocalPath.getParentFile().mkdirs();
  }

  public String getTag() {
    return myTag;
  }

  public String getGlobalPath() {
    return myGlobalPath;
  }

  public File getLocalPath() {
    return myLocalPath;
  }

  public void create(String reason) throws CCException {
    try {
      if (exists()) {
        throw new CCException(String.format("The view \"%s\" already exists", getTag()));
      }
      final VobObjectParser result = CTool.createSnapshotView(getTag(), myLocalPath, reason);
      myGlobalPath = result.getGlobalPath();
      setConfigSpec(myConfigSpecs);

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public CCDelta[] setConfigSpec(final List<String> configSpec) throws CCException {
    try {
      LOG.debug(String.format("", getTag(), configSpec));
      myConfigSpecs = new ArrayList<String>(configSpec);
      return wrap(CTool.setConfigSpecs(myLocalPath, myConfigSpecs));

    } catch (Exception e) {
      throw new CCException(e);
    }

  }

  public List<String> getConfigSpec() throws CCException {
    try {
      myConfigSpecs = new ArrayList<String>(CTool.getConfigSpecs(getTag()));
      return myConfigSpecs;

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public void add(File file, String reason) throws CCException {
    try {
      // TODO: check the File inside the View
      CTool.checkout(myLocalPath, file.getParentFile(), reason);
      CTool.mkelem(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file.getParentFile(), reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public void checkout(File file, String reason) throws CCException {
    try {
      // TODO: check the File inside the View
      CTool.checkout(myLocalPath, file, reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public void checkin(File file, String reason) throws CCException {
    try {
      // TODO: check the File inside the View
      CTool.checkin(myLocalPath, file, reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public void remove(File file, String reason) throws CCException {
    try {
      // TODO: check the File inside the View
      CTool.checkout(myLocalPath, file.getParentFile(), reason);
      CTool.rmname(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file.getParentFile(), reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public void remove(File file, String version, String reason) throws CCException {
    try {
      CTool.rmver(myLocalPath, file, version, reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
  public void drop(File file, String reason) throws CCException {
    try {
      CTool.rmelem(myLocalPath, file, reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
  private CCDelta[] wrap(final ChangeParser[] parsers) {
    final ArrayList<CCDelta> out = new ArrayList<CCDelta>(parsers.length);
    for (final ChangeParser change : parsers) {
      out.add(new CCDelta(this, change.isAddition(), change.isChange(), change.isDeletion(), change.getLocalPath(), change.getRevisionBefor(), change.getRevisionAfter()));
    }
    return out.toArray(new CCDelta[out.size()]);
  }

  public boolean exists() throws CCException {
    final CCRegion region = new CCRegion();
    for (CCSnapshotView view : region.getViews()) {
      if (view.getTag().trim().equals(getTag().trim())) {
        // TODO: move initialization to proper place
        myGlobalPath = view.getGlobalPath();
        myLocalPath = view.getLocalPath();
        myConfigSpecs = new ArrayList<String>(view.getConfigSpec());
        return true;
      }
    }
    return false;
  }

  public boolean isAlive() throws CCException {
    try {
      CTool.lsView(getLocalPath());
      return true;
    } catch (Exception e) {
      LOG.debug(e);
      return false;
    }
  }

  public void drop() throws CCException {
    try {
      CTool.dropView(getGlobalPath());

    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  @Override
  public String toString() {
    return String.format("{CCSnapshotView: tag=\"%s\", global=\"%s\" local=\"%s\"}", getTag(), getGlobalPath(), getLocalPath());
  }
  
}
