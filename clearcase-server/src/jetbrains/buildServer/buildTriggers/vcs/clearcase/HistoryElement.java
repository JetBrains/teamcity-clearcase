/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Constants;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HistoryElement {
  private static final Logger LOG = Logger.getLogger(HistoryElement.class);

  private static final Pattern CC_LSHISTORY_VPATH_PATTERN = Pattern.compile("(.*?)[/\\\\](\\d*)[/\\\\](.*?)[/\\\\](.*)");
  private static final Pattern CC_LSHISTORY_VFILE_PATTERN = Pattern.compile("(.*?)[/\\\\](\\d*)[/\\\\](.*)");
  private static final Pattern CC_LSHISTORY_VEND_PATTERN =  Pattern.compile("(.*?)[/\\\\](.*?)[/\\\\](\\d*)");

  private final String myUser;
  private final String myDateString;
  private final Date myDate;
  private final String myObjectName;
  private final String myObjectKind;
  private final String myObjectVersion;
  private final String myOperation;
  private final String myEvent;
  private final String myComment;
  private final String myActivity;
  private final long myEventID;

  private static final int EXPECTED_CHANGE_FIELD_COUNT = 9;
  private static final String EVENT = "event ";
  private static final DateFormat ourDateFormat = new SimpleDateFormat(CCParseUtil.OUTPUT_DATE_FORMAT);

  private HistoryElement(final String eventId,
                         final String user,
                         final String dateString,
                         final String objectName,
                         final String objectKind,
                         final String objectVersion,
                         final String operation,
                         final String event,
                         final String comment,
                         final String activity) throws ParseException {
    myEventID = Long.parseLong(eventId);
    myUser = user;
    myDateString = dateString;
    myDate = ourDateFormat.parse(dateString);
    myObjectName = normalizeLsHistoryFileName(objectName);
    myObjectKind = objectKind;
    myObjectVersion = objectVersion;
    myOperation = operation;
    myEvent = event;
    myComment = comment;
    myActivity = activity;
  }

  private static HistoryElement createHistoryElement(final String eventId,
                                                     final String user,
                                                     final String date,
                                                     final String objectName,
                                                     final String objectKind,
                                                     final String objectVersion,
                                                     final String operation,
                                                     final String event,
                                                     final String comment,
                                                     final String activity) throws ParseException {
    String kind = objectKind, version = objectVersion;
    if ("rmver".equals(operation) && "destroy version on branch".equals(event)) {
      final String extractedVersion = extractVersion(comment);
      if (extractedVersion != null) {
        kind = "version";
        version = extractedVersion;
      }
    }
    return new HistoryElement(eventId, user, date, objectName, kind, version, operation, event, comment, activity);

  }

  @Nullable
  private static String extractVersion(final String comment) {
    int firstPos = comment.indexOf("\""), lastPos = comment.lastIndexOf("\"");
    if (firstPos != -1 && lastPos != -1 && firstPos < lastPos) {
      return comment.substring(firstPos + 1, lastPos);
    }
    return null;
  }

  public static HistoryElement readFrom(final String line) throws ParseException {
    if (!line.startsWith(EVENT)) {
      return null;
    }
    final String[] parts = line.substring(EVENT.length()).split(":", 2);
    if (parts.length < 2) {
      return null;
    }
    final String eventId = parts[0].trim();
    final String[] strings = parts[1].trim().split(ClearCaseConnection.DELIMITER, EXPECTED_CHANGE_FIELD_COUNT);
    if (strings.length < EXPECTED_CHANGE_FIELD_COUNT - 1) {
      return null;
    } else if (strings.length == EXPECTED_CHANGE_FIELD_COUNT - 1) {
      return createHistoryElement(eventId, strings[0], strings[1], strings[2], strings[3], strings[4], strings[5], strings[6], strings[7], "");
    } else {
      return createHistoryElement(eventId, strings[0], strings[1], strings[2], strings[3], strings[4], strings[5], strings[6], strings[7], strings[8]);
    }
  }

  public String getDateString() {
    return myDateString;
  }

  public Date getDate() {
    return myDate;
  }

  public String getObjectName() {
    return myObjectName;
  }

  public String getObjectKind() {
    return myObjectKind;
  }

  public String getObjectVersion() {
    return myObjectVersion;
  }

  public String getOperation() {
    return myOperation;
  }

  public String getEvent() {
    return myEvent;
  }

  public String getComment() {
    return myComment;
  }

  public String getUser() {
    return myUser;
  }

  public int getObjectVersionInt() {
    return CCParseUtil.getVersionInt(myObjectVersion);
  }

  public String getObjectLastBranch() {
    return CCParseUtil.getLastBranch(myObjectVersion);
  }

  public long getEventID() {
    return myEventID;
  }

  public String getPreviousVersion(final ClearCaseConnection connection, final boolean isDirPath) throws VcsException, IOException {
    return connection.getPreviousVersion(this, isDirPath);
  }

  public boolean versionIsInsideView(final ClearCaseConnection connection, final boolean isFile) throws IOException, VcsException {
    final boolean result = connection.versionIsInsideView(myObjectName, getObjectVersion(), isFile);
    LOG.debug(String.format(result
                            ? "Version \"%s\" of file \"%s\" is inside view"
                            : "Version \"%s\" of file \"%s\" is not inside view", myObjectVersion, myObjectName));
    return result;
  }

  public String getActivity() {
    return myActivity;
  }

  public String getLogRepresentation() {
    return "\"" + getObjectName() + "\", version \"" + getObjectVersion() + "\", date \"" + getDateString() + "\", operation \"" + getOperation() + "\", event \"" + getEvent() + "\"";
  }

  @Override
  public String toString() {
    return String.format("%s: %s(%s)=>%s", getEventID(), getObjectName(), getOperation(), getEvent());
  }

  public static String normalizeLsHistoryFileName(final @NotNull String lsHistoryFileName, boolean dropVersions) {
    if (!TeamCityProperties.getBoolean(Constants.TEAMCITY_PROPERTY_DISABLE_HISTORY_ELEMENT_TRANSFORMATION)) {
      final StringBuffer out = new StringBuffer();
      final int vsepPos = lsHistoryFileName.indexOf(CCParseUtil.CC_VERSION_SEPARATOR);
      if (vsepPos != -1) {
        //looking for expected separator
        final String targetPathSeparator;
        if (lsHistoryFileName.indexOf("/") != -1) {
          targetPathSeparator = "/";
        } else {
          targetPathSeparator = "\\";
        }
        //split...
        String head = lsHistoryFileName.substring(0, vsepPos);
        String tail = lsHistoryFileName.substring(vsepPos + 3, lsHistoryFileName.length());//exclude @@ and next slash
        //drop versions    
        out.append(head);
        Matcher matcher;
        while (true) {
          matcher = CC_LSHISTORY_VPATH_PATTERN.matcher(tail);
          if (matcher.matches()) {
            //branch=matcher.group(1)
            //version=matcher.group(2)            
            final String pathElement = matcher.group(3);
            tail = matcher.group(4);
            if (dropVersions) {
              out.append(targetPathSeparator).append(pathElement);
            } else {
              out.append(out.charAt(out.length() - 1) == '@' ? "" : "@@").append(targetPathSeparator).append(matcher.group(1)).append(targetPathSeparator).append(matcher.group(2)).append(targetPathSeparator).append(pathElement);
            }
            continue;

          } else {
            matcher = CC_LSHISTORY_VFILE_PATTERN.matcher(tail);
            if (matcher.matches()) {
              //branch=matcher.group(1)
              //version=matcher.group(2)            
              final String fileElement = matcher.group(3);
              if (dropVersions) {
                out.append(targetPathSeparator).append(fileElement);
              } else {
                out.append(out.charAt(out.length() - 1) == '@' ? "" : "@@").append(targetPathSeparator).append(matcher.group(1)).append(targetPathSeparator).append(matcher.group(2)).append(targetPathSeparator).append(fileElement);
              }
              
            } else {
              matcher = CC_LSHISTORY_VEND_PATTERN.matcher(tail);
              if (matcher.matches()) {
                if (!dropVersions) {
                  out.append(out.charAt(out.length() - 1) == '@' ? "" : "@@").append("/").append(tail);
                }
              }
            }
          }
          break;
        }
        final String processed = out.toString().trim();
        if (LOG.isDebugEnabled()) {
          LOG.debug(String.format("Path element transformed: \"%s\"->\"%s\"", lsHistoryFileName, processed));
        }
        return processed;
      }
    }
    return lsHistoryFileName;
  }

  /**
   * according to TW-13359 the file name element can include versions segments if a change came from another branch.
   * must drop its see also: TW-14378
   */
  public static String normalizeLsHistoryFileName(final @NotNull String lsHistoryFileName) {
    return normalizeLsHistoryFileName(lsHistoryFileName, false);
  }

}
