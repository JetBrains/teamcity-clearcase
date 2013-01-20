/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.NoSuchElementException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class HistoryElementProvider implements HistoryElementIterator {
  @NotNull private final BufferedReader myReader;
  @Nullable private HistoryElement myNextElement;
  @Nullable private String myNextLine;

  public HistoryElementProvider(@NotNull final InputStream inputStream) throws IOException {
    myReader = new BufferedReader(new InputStreamReader(inputStream));
    myNextLine = myReader.readLine();
    readNext();
  }

  @NotNull
  public HistoryElement next() throws IOException {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    try {
      //noinspection ConstantConditions
      return myNextElement;
    }
    finally {
      readNext();
    }
  }

  public boolean hasNext() {
    return myNextElement != null;
  }

  public void close() throws IOException {
    myReader.close();
  }

  private void readNext() throws IOException {
    String line = myNextLine;
    while (line != null) {
      myNextLine = myReader.readLine();
      if (!line.endsWith(ClearCaseConnection.LINE_END_DELIMITER)) {
        while (myNextLine != null && !line.endsWith(ClearCaseConnection.LINE_END_DELIMITER)) {
          line += '\n' + myNextLine;
          myNextLine = myReader.readLine();
        }
      }
      if (line.endsWith(ClearCaseConnection.LINE_END_DELIMITER)) {
        line = line.substring(0, line.length() - ClearCaseConnection.LINE_END_DELIMITER.length());
      }
      myNextElement = parseChange(line);
      if (myNextElement != null) {
        return;
      }
      line = myNextLine;
    }
    myNextElement = null;
  }

  @Nullable
  private static HistoryElement parseChange(@NotNull final String line) {
    try {
      return HistoryElement.readFrom(line);
    }
    catch (final ParseException e) {
      return null;
    }
  }
}
