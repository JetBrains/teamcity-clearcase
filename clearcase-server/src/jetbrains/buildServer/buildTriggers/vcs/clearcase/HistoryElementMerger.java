/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
import java.util.Date;
import java.util.NoSuchElementException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HistoryElementMerger implements HistoryElementIterator {
  @NotNull private final HistoryElementIterator myFirstIterator;
  @NotNull private final HistoryElementIterator mySecondIterator;
  @Nullable private HistoryElement myFirstNextElement;
  @Nullable private HistoryElement mySecondNextElement;

  public HistoryElementMerger(@NotNull final HistoryElementIterator firstIterator,
                              @NotNull final HistoryElementIterator secondIterator) throws IOException {
    myFirstIterator = firstIterator;
    mySecondIterator = secondIterator;
    readFirstNext();
    readSecondNext();
  }

  @NotNull
  public HistoryElement next() throws IOException {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    if (myFirstNextElement == null) {
      return getSecondNext();
    }
    else if (mySecondNextElement == null) {
      return getFirstNext();
    }
    else {
      return isFirstNewer() ? getFirstNext() : getSecondNext();
    }
  }

  private boolean isFirstNewer() {
    //noinspection ConstantConditions
    final Date firstDate = myFirstNextElement.getDate(), secondDate = mySecondNextElement.getDate();
    //noinspection ConstantConditions
    return firstDate.equals(secondDate) // event ID order can be not the same as date order in case of using VOB replicas
           ? myFirstNextElement.getEventID() > mySecondNextElement.getEventID()
           : firstDate.after(secondDate);
  }

  public boolean hasNext() {
    return myFirstNextElement != null || mySecondNextElement != null;
  }

  public void close() throws IOException {
    myFirstIterator.close();
    mySecondIterator.close();
  }

  private HistoryElement getFirstNext() throws IOException {
    try {
      //noinspection ConstantConditions
      return myFirstNextElement;
    }
    finally {
      readFirstNext();
    }
  }

  private HistoryElement getSecondNext() throws IOException {
    try {
      //noinspection ConstantConditions
      return mySecondNextElement;
    }
    finally {
      readSecondNext();
    }
  }

  private void readFirstNext() throws IOException {
    myFirstNextElement = readNext(myFirstIterator);
  }

  private void readSecondNext() throws IOException {
    mySecondNextElement = readNext(mySecondIterator);
  }

  @Nullable
  private HistoryElement readNext(@NotNull final HistoryElementIterator iterator) throws IOException {
    return iterator.hasNext() ? iterator.next() : null;
  }
}
