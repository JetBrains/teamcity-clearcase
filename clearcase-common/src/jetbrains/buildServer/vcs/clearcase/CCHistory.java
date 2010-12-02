package jetbrains.buildServer.vcs.clearcase;

import java.io.File;
import java.util.Date;

import jetbrains.buildServer.vcs.clearcase.CTool.HistoryParser;

public class CCHistory {

  private CCSnapshotView myView;

  private Kind myKind;

  private Date myDate;

  private File myFile;

  private String myVersion;

  private String myComment;

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

}
