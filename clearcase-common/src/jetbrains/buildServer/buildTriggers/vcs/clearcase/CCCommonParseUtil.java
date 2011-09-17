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

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class CCCommonParseUtil {
  @NonNls @NotNull public static final String OUTPUT_DATE_FORMAT = "yyyyMMdd.HHmmss";
  @NonNls @NotNull private static final String INPUT_DATE_FORMAT = "dd-MMMM-yyyy.HH:mm:ss";
  @NotNull private static final ThreadLocal<DateFormat> ourDateFormat = new ThreadLocal<DateFormat>() {
    @Override
    protected DateFormat initialValue() {
      return new SimpleDateFormat(INPUT_DATE_FORMAT, Locale.US);
    }
  };

  public static Date parseDate(final String currentVersion) throws ParseException {
    return getDateFormat().parse(currentVersion);
  }

  public static String formatDate(final Date date) {
    return getDateFormat().format(date);
  }

  public static long parseLong(@NotNull final String longStr) throws ParseException {
    try {
      return Long.parseLong(longStr);
    }
    catch (final NumberFormatException e) {
      final ParseException parseException = new ParseException(longStr, 0);
      parseException.initCause(e);
      throw parseException;
    }
  }

  private static DateFormat getDateFormat() {
    return ourDateFormat.get();
  }
}
