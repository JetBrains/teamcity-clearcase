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
import java.util.Date;
import java.util.List;

import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.clearcase.CTool.ChangeParser;
import jetbrains.buildServer.vcs.clearcase.CTool.HistoryParser;
import jetbrains.buildServer.vcs.clearcase.CTool.StreamParser;
import jetbrains.buildServer.vcs.clearcase.CTool.ViewParser;
import jetbrains.buildServer.vcs.clearcase.CTool.VobObjectParser;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CCSnapshotView {

  private static final Logger LOG = Logger.getLogger(CTool.class);

  protected ArrayList<String> myConfigSpecs = new ArrayList<String>();
  protected File myLocalPath;
  protected File myGlobalPath;
  protected String myTag;

  private boolean isUcm = false;

  private String myStream;

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
      final CCSnapshotView view = new CCSnapshotView(parser.getRegion(), parser.getServerHost(), parser.getTag(), new File(parser.getGlobalPath()), parser.getAttributes().contains(ViewParser.ATTRIBUTE_UCM));
      view.myLocalPath = localRoot;
      return view;
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  CCSnapshotView(final String region, final String server, final String tag, final File glolbalPath, final boolean isUcm) {
    myTag = tag;
    myGlobalPath = glolbalPath;
    this.isUcm = isUcm;
  }

  public CCSnapshotView(final String tag, final File localPath) {
    myTag = tag;
    myLocalPath = localPath;
  }

  //TODO: review constructors 
  public CCSnapshotView(String buildViewTag, @Nullable String stream, File globalViewLocation, File localPath) {
    this(buildViewTag, localPath);
    myGlobalPath = globalViewLocation;
    myStream = stream;
  }

  public boolean isUcm() {
    return isUcm;
  }

  public String getTag() {
    return myTag;
  }

  public File getGlobalPath() {
    return myGlobalPath;
  }

  public File getLocalPath() {
    return myLocalPath;
  }

  public String getStream() throws CCException {
    if (isUcm() && myStream == null) {
      try {
        final StreamParser stream = CTool.lsStream(getTag());
        if (stream == null) {
          throw new CCException(String.format("Could not find stream for \"%s\"", getTag()));
        }
        myStream = String.format("%s@%s%s", stream.getName(), File.separator, stream.getProjectSelector());
      } catch (InterruptedException e) {
        throw new CCException(e);
      } catch (IOException e) {
        throw new CCException(e);
      }
    }
    return myStream;
  }

  public void create(String reason) throws CCException {
    try {
      if (exists()) {
        throw new CCException(String.format("The view \"%s\" already exists", getTag()));
      }
      final VobObjectParser result;
      if (myGlobalPath != null) {
        result = CTool.createSnapshotView(getTag(), getStream(), myGlobalPath.getAbsolutePath(), myLocalPath.getAbsolutePath(), reason);
      } else {
        result = CTool.createSnapshotView(getTag(), getStream(), null, myLocalPath.getAbsolutePath(), reason);
      }
      myGlobalPath = new File(result.getGlobalPath());
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

  public boolean isCheckedout(final @NotNull File file) throws CCException {
    try {
      return CTool.isCheckedout(myLocalPath, file);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  //TODO: move to testing implementation
  protected void add(final @NotNull File file, String reason) throws CCException {
    try {
      //check parent folder is checked out and checkout if no so
      final File folder = file.getParentFile();
      if (!CTool.isCheckedout(myLocalPath, folder)) {
        CTool.checkout(myLocalPath, folder, reason);
      }
      CTool.mkelem(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, folder, reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected void checkout(final @NotNull File file, String reason) throws CCException {
    try {
      // TODO: check the File inside the View
      CTool.checkout(myLocalPath, file, reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected void checkin(final @NotNull File file, String reason) throws CCException {
    try {
      // TODO: check the File inside the View
      CTool.checkin(myLocalPath, file, reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected void remove(File file, String reason) throws CCException {
    try {
      // TODO: check the File inside the View
      CTool.checkout(myLocalPath, file.getParentFile(), reason);
      CTool.rmname(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file.getParentFile(), reason);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  protected void remove(File file, String version, String reason) throws CCException {
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

  public CCHistory[] getHistory(File file) throws CCException {
    try {
      final HistoryParser[] history = CTool.lsHistory(file, false);
      return wrap(history);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public CCDelta[] update(final @NotNull File path) throws CCException {
    try {
      final ChangeParser[] history = CTool.update(path, new Date());
      return wrap(history);
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  private CCHistory[] wrap(HistoryParser[] parsers) {
    final ArrayList<CCHistory> out = new ArrayList<CCHistory>(parsers.length);
    for (final HistoryParser parser : parsers) {
      out.add(new CCHistory(this, parser));
    }
    return out.toArray(new CCHistory[out.size()]);
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
      CTool.lsView(getTag());
      return true;
    } catch (Exception e) {
      LOG.debug(e);
      return false;
    }
  }

  /**
   * Regenerates view.dat from the CC Server
   * 
   * @throws CCException
   */
  public CCSnapshotView restore() throws CCException {
    try {
      if (!getLocalPath().exists()) {
        getLocalPath().mkdirs();
      }
      final ViewParser parser = CTool.lsView(getTag());
      final String viewUuid = parser.getUUID();
      final String content = String.format("ws_oid:00000000000000000000000000000000 view_uuid:%s", viewUuid.replace(".", "").replace(":", ""));
      final File viewDataFile = new File(getLocalPath(), "view.dat");
      FileUtil.writeFile(viewDataFile, content);
      return this;
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public CCSnapshotView drop() throws CCException {
    try {
      CTool.dropView(getGlobalPath().getAbsolutePath());
      return this;
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  @Override
  public String toString() {
    return String.format("{CCSnapshotView: tag=\"%s\", global=\"%s\" local=\"%s\"}", getTag(), getGlobalPath(), getLocalPath());
  }

}
