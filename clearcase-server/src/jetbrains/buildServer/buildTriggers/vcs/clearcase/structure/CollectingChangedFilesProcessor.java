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

package jetbrains.buildServer.buildTriggers.vcs.clearcase.structure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.*;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.versionTree.Version;
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;


class CollectingChangedFilesProcessor implements ChangedFilesProcessor {
  private final ClearCaseConnection myConnection;
  private final List<ChangedElementInfo> myChangedElements;

  public CollectingChangedFilesProcessor(final ClearCaseConnection connection) {
    myConnection = connection;
    myChangedElements = new ArrayList<ChangedElementInfo>();
  }

  public void processChangedFile(@NotNull final HistoryElement element) throws VcsException {
    final String path = element.getObjectName();
    final Version lastVersion = myConnection.getLastVersion(path, true);
    if (lastVersion != null) {
      final String lastVersionString = lastVersion.getWholeName();
      if (lastVersionString != null) {
        myChangedElements.add(new ChangedElementInfo(getRelativePath(path), lastVersionString, ChangedElementInfo.ChangeType.CHANGED_FILE));
      }
    }
  }

  public void processDestroyedFileVersion(@NotNull final HistoryElement element) {
    final String path = element.getObjectName();
    myChangedElements.add(new ChangedElementInfo(getRelativePath(path), element.getObjectVersion(), ChangedElementInfo.ChangeType.DELETED_VERSION));
  }

  public void processChangedDirectory(@NotNull final HistoryElement element) throws
                                                                    IOException, VcsException {
    final String path = element.getObjectName();
    final String elementVersion = element.getObjectVersion();

    final ChangedElementInfo parentChangedDir =
      new ChangedElementInfo(getRelativePath(path), elementVersion, ChangedElementInfo.ChangeType.CHANGED_DIR);
    myChangedElements.add(parentChangedDir);
    CCParseUtil.processChangedDirectory(element, myConnection, new ChangedStructureProcessor() {
      public void fileAdded(@NotNull final SimpleDirectoryChildElement simpleChild) throws VcsException {
        final DirectoryChildElement child = simpleChild.createFullElement(myConnection);
        if (child != null) {
          parentChangedDir.addAddedElement(new ChangedElementInfo(getRelativePath(child.getPath()),
                                                                  child.getStringVersion(),
                                                                  ChangedElementInfo.ChangeType.ADDED_FILE));
        }
      }

      public void fileDeleted(@NotNull final SimpleDirectoryChildElement simpleChild) {
        myChangedElements.add(new ChangedElementInfo(getRelativePath(simpleChild), null, ChangedElementInfo.ChangeType.DELETED_FILE));
      }

      public void directoryDeleted(@NotNull final SimpleDirectoryChildElement simpleChild) {
        myChangedElements.add(new ChangedElementInfo(getRelativePath(simpleChild), null, ChangedElementInfo.ChangeType.DELETED_DIR));
      }

      public void directoryAdded(@NotNull final SimpleDirectoryChildElement simpleChild) throws VcsException {
        final DirectoryChildElement child = simpleChild.createFullElement(myConnection);
        if (child != null) {
          final String relPath = getRelativePath(child.getPath());
          CorrectingAddedDirectoryVersionProcessor.processAddedDirectory(relPath, child.getFullPath(), child.getStringVersion(), myConnection,
                                                                         parentChangedDir);
        }
      }
    });
  }

  @NotNull
  private String getRelativePath(@NotNull final SimpleDirectoryChildElement simpleChild) {
    return myConnection.getRelativePath(simpleChild);
  }

  private String getRelativePath(final String path) {
    return myConnection.getRelativePath(path);
  }

  public List<ChangedElementInfo> getChanges() {
    return myChangedElements;
  }
}
