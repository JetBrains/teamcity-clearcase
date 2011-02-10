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

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.Dates;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public class CCParseUtil {
  @NotNull private static final Logger LOG = Logger.getLogger(CCParseUtil.class);

  @NonNls public static final String CC_VERSION_SEPARATOR = "@@";
  @NonNls public static final String OUTPUT_DATE_FORMAT = "yyyyMMdd.HHmmss";
  @NonNls private static final String INPUT_DATE_FORMAT = "dd-MMMM-yyyy.HH:mm:ss";
  @NonNls private static final String DIRECTORY_ELEMENT = "directory element";
  @NonNls private static final String FILE_ELEMENT = "file element";
  @NonNls private static final String NOT_LOADED = "[not loaded]";

  private CCParseUtil() {}

  @NotNull
  public static List<DirectoryChildElement> readDirectoryVersionContent(@NotNull final ClearCaseConnection connection, @NotNull final String dirPathWithVersion) throws VcsException {
    final List<SimpleDirectoryChildElement> simpleChildren = connection.getChildren(dirPathWithVersion);
    final List<DirectoryChildElement> children = new ArrayList<DirectoryChildElement>(simpleChildren.size());
    for (SimpleDirectoryChildElement simpleChild : simpleChildren) {
      final DirectoryChildElement child = simpleChild.createFullElement(connection);
      if (child != null) {
        children.add(child);
      }
    }
    return children;
  }

  public static void processChangedFiles(final ClearCaseConnection connection,
                                         final String fromVersion,
                                         final String currentVersion,
                                         final ChangedFilesProcessor fileProcessor) throws ParseException, IOException, VcsException {
    final @Nullable Date lastDate = currentVersion != null ? parseDate(currentVersion) : null;

    final HistoryElementIterator iterator = getChangesIterator(connection, fromVersion);

    final ChangesInverter inverter = new ChangesInverter(fileProcessor);

    try {
      while (iterator.hasNext()) {
        final HistoryElement element = iterator.next();
        LOG.debug("Processing event: " + element.getLogRepresentation());
        if (connection.isInsideView(element.getObjectName())) {
          if (lastDate == null || element.getDate().before(lastDate)) {
            if ("checkin".equals(element.getOperation())) {
              if ("create directory version".equals(element.getEvent())) {
                if (element.versionIsInsideView(connection, false) && connection.fileExistsInParents(element, false)) {
                  inverter.processChangedDirectory(element);
                }
              } else if ("create version".equals(element.getEvent())) {
                if (element.versionIsInsideView(connection, true) && connection.fileExistsInParents(element, true)) {
                  inverter.processChangedFile(element);
                }
              }
            } else if ("rmver".equals(element.getOperation())) {
              if ("destroy version on branch".equals(element.getEvent()) && connection.fileExistsInParents(element, true)) {
                inverter.processDestroyedFileVersion(element);
              }
            }
          }
        }
      }
    }
    finally {
      iterator.close();
    }

    inverter.processCollectedChangesInInvertedOrder();
  }

  // see http://devnet.jetbrains.net/message/5273615
  private static HistoryElementIterator getChangesIterator(final ClearCaseConnection connection, final String fromVersion) throws IOException, VcsException, ParseException {
    final HistoryElementIterator iterator = connection.getChangesIterator(fromVersion);
    if (!connection.isUCM()) {
      return iterator;
    }
    final long delay = TeamCityProperties.getInteger(Constants.LSHISTORY_UCM_DELAY, 1/*0*/) * Dates.ONE_SECOND;
    final long threshold = new Date().getTime();
    int eventCount_1, eventCount_2 = getEventCount(iterator, threshold);
    do {
      try {
        Util.sleep(/*delay)Thread.sleep(*/delay);
      } catch (final InterruptedException ignore) {}
      eventCount_1 = eventCount_2;
      eventCount_2 = getEventCount(connection.getChangesIterator(fromVersion), threshold);
    } while (eventCount_1 != eventCount_2);
    System.err.println(String.format("%s: %s", CCParseUtil.class.getSimpleName(), Util.dump()));
    return connection.getChangesIterator(fromVersion);
  }

  private static int getEventCount(final HistoryElementIterator iterator, final long threshold) throws IOException, ParseException {
    try {
      int count = 0;
      while (iterator.hasNext()) {
        if (iterator.next().getDate().getTime() < threshold) {
          count++;
        }
      }
      return count;
    }
    finally {
      iterator.close();
    }
  }

  private static Date parseDate(final String currentVersion) throws ParseException {
    return getDateFormat().parse(currentVersion);
  }

  public static String formatDate(final Date date) {
    return getDateFormat().format(date);
  }

  public static void processChangedDirectory(final HistoryElement element,
                                             final ClearCaseConnection connection,
                                             ChangedStructureProcessor processor) throws IOException, VcsException {

    if (element.getObjectVersionInt() > 0) {
      final String before = element.getObjectName() + CC_VERSION_SEPARATOR + element.getPreviousVersion(connection, true);
      final String after = element.getObjectName() + CC_VERSION_SEPARATOR + element.getObjectVersion();

      final List<SimpleDirectoryChildElement> elementsBefore = connection.getChildren(before);
      final List<SimpleDirectoryChildElement> elementsAfter = connection.getChildren(after);

      final Map<String, SimpleDirectoryChildElement> filesBefore = collectMap(elementsBefore);
      final Map<String, SimpleDirectoryChildElement> filesAfter = collectMap(elementsAfter);

      for (final String fileName : filesBefore.keySet()) {
        if (!filesAfter.containsKey(fileName)) {
          final SimpleDirectoryChildElement sourceElement = filesBefore.get(fileName);
          switch (sourceElement.getType()) {
            case DIRECTORY:
              processor.directoryDeleted(sourceElement);
              break;
            case FILE:
              processor.fileDeleted(sourceElement);
              break;
          }
        }
      }

      for (final String fileName : filesAfter.keySet()) {
        if (!filesBefore.containsKey(fileName)) {
          final SimpleDirectoryChildElement targetElement = filesAfter.get(fileName);
          switch (targetElement.getType()) {
            case DIRECTORY:
              processor.directoryAdded(targetElement);
              break;
            case FILE:
              processor.fileAdded(targetElement);
              break;
          }
        }
      }

    }
  }

  @NotNull
  private static Map<String, SimpleDirectoryChildElement> collectMap(@NotNull final List<SimpleDirectoryChildElement> elementsBefore) {
    final HashMap<String, SimpleDirectoryChildElement> result = new HashMap<String, SimpleDirectoryChildElement>();
    for (final SimpleDirectoryChildElement element : elementsBefore) {
      result.put(element.getName(), element);
    }
    return result;
  }

  public static SimpleDateFormat getDateFormat() {
    return new SimpleDateFormat(INPUT_DATE_FORMAT, Locale.US);
  }

  public static String getFileName(final String subdirectory) {
    return new File(subdirectory).getName();
  }

  public static int getVersionInt(final String wholeVersion) {
    final int versSeparator = wholeVersion.lastIndexOf(File.separator);
    return Integer.parseInt(wholeVersion.substring(versSeparator + 1));
  }

  public static String getLastBranch(final String wholeVersion) {
    final int lastSeparatorPos = wholeVersion.lastIndexOf(File.separator);
    final int preLastSeparatorPos = wholeVersion.lastIndexOf(File.separator, lastSeparatorPos - 1);
    return wholeVersion.substring(preLastSeparatorPos + 1, lastSeparatorPos);
  }

  @Nullable
  public static SimpleDirectoryChildElement readChildFromLSFormat(@NotNull final String line) {
    final DirectoryChildElement.Type type;
    String currentPath = line;
    if (currentPath.startsWith(DIRECTORY_ELEMENT)) {
      currentPath = currentPath.substring(DIRECTORY_ELEMENT.length()).trim();
      type = DirectoryChildElement.Type.DIRECTORY;
    }
    else if (currentPath.startsWith(FILE_ELEMENT)){
      type = DirectoryChildElement.Type.FILE;
      currentPath = currentPath.substring(FILE_ELEMENT.length()).trim();
    }
    else {
      type = null;
    }

    if (currentPath.endsWith(NOT_LOADED)) {
      currentPath = currentPath.substring(0, currentPath.length() - NOT_LOADED.length()).trim();
    }

    if (currentPath.endsWith(CC_VERSION_SEPARATOR)) {
      currentPath = currentPath.substring(0, currentPath.length() - CC_VERSION_SEPARATOR.length()).trim();
    }

    if (type != null) {
      return new SimpleDirectoryChildElement(currentPath, type);
    }

    return null;
  }
}
