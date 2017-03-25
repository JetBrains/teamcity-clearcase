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

package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public interface HistoryElementIterator {
  @NotNull
  HistoryElement next() throws IOException;

  boolean hasNext();

  void close() throws IOException;

  @NotNull HistoryElementIterator EMPTY = new HistoryElementIterator() {
    @NotNull
    public HistoryElement next() {
      throw new UnsupportedOperationException();
    }

    public boolean hasNext() {
      return false;
    }

    public void close() {}
  };
}
