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

package jetbrains.buildServer.vcs.clearcase;

import java.io.File;
import java.util.Date;
import jetbrains.buildServer.vcs.clearcase.CTool.HistoryParser;

public class CCHistory {

  private final CCSnapshotView myView;

  private Kind myKind;

  private final Date myDate;

  private final File myFile;

  private final String myVersion;

  private final String myComment;
  
  public CCHistory(CCSnapshotView view, File file, Kind kind, Date date, String version, String comment) {
    myView = view;
    myKind = kind;
    myDate = date;
    myFile = file;
    myVersion = version;
    myComment = comment;
  }

  public CCHistory(CCSnapshotView view, HistoryParser parser) {
    myView = view;
    if ("mkelem".equals(parser.operation)) {
      myKind = Kind.ADDITION;

    } else if ("checkin".equals(parser.operation)) {
      myKind = Kind.MODIFICATION;

    } else if ("rmname".equals(parser.operation)) {
      myKind = Kind.DELETION;
      
    } else if ("rmver".equals(parser.operation)) {
      myKind = Kind.DROP_VERSION;

    } else if ("rmelem".equals(parser.operation)) {
      myKind = Kind.DROP_ELEMENT;

    }

    myDate = parser.date;

    myFile = new File(parser.path);

    myVersion = parser.version;

    myComment = parser.comment;

  }

  public CCSnapshotView getView() {
    return myView;
  }

  public Kind getKind() {
    return myKind;
  }

  public Date getDate() {
    return myDate;
  }

  public File getFile() {
    return myFile;
  }

  public String getVersion() {
    return myVersion;
  }

  public String getComment() {
    return myComment;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    final CCHistory ccHistory = (CCHistory)o;

    if (myFile != null ? !myFile.equals(ccHistory.myFile) : ccHistory.myFile != null) return false;
    if (myVersion != null ? !myVersion.equals(ccHistory.myVersion) : ccHistory.myVersion != null) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myFile != null ? myFile.hashCode() : 0;
    result = 31 * result + (myVersion != null ? myVersion.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return String.format("date='%s' version='%s', kind='%s', file='%s' ", getDate(), getVersion(), getKind(), getFile());
  }

}
