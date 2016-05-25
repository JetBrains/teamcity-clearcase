/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
import java.util.ArrayList;
import java.util.List;
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;

/**
 * @author Maxim.Manuylov
 *         Date: 29.04.2010
 */
public class ChangesInverter implements ChangedFilesProcessor {
  @NotNull private final ChangedFilesProcessor myBaseProcessor;
  @NotNull private final List<Change> myInvertedChanges = new ArrayList<Change>();

  public ChangesInverter(@NotNull final ChangedFilesProcessor baseProcessor) {
    myBaseProcessor = baseProcessor;
  }

  public void processChangedFile(@NotNull final HistoryElement element) throws VcsException, IOException {
    addChange(new Change() {
      public void process() throws IOException, VcsException {
        myBaseProcessor.processChangedFile(element);
      }
    });
  }

  public void processChangedDirectory(@NotNull final HistoryElement element) throws IOException, VcsException {
    addChange(new Change() {
      public void process() throws IOException, VcsException {
        myBaseProcessor.processChangedDirectory(element);
      }
    });
  }

  public void processDestroyedFileVersion(@NotNull final HistoryElement element) throws VcsException {
    addChange(new Change() {
      public void process() throws VcsException {
        myBaseProcessor.processDestroyedFileVersion(element);
      }
    });
  }

  public void processCollectedChangesInInvertedOrder() throws IOException, VcsException {
    for (final Change change : myInvertedChanges) {
      change.process();
    }
  }

  private void addChange(@NotNull final Change change) {
    myInvertedChanges.add(0, change);
  }

  private static interface Change {
    void process() throws IOException, VcsException;
  }
}
