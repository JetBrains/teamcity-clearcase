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

import java.text.ParseException;
import java.util.Date;
import java.util.List;
import jetbrains.buildServer.util.Dates;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class Revision {
  @NonNls @NotNull private static final String FIRST = "FIRST";
  @NonNls @NotNull private static final String SEPARATOR = "@"; // if you want to change it to char, note that it will be casted to int in RevisionImpl.asString(), so be careful
  @NonNls @NotNull private static final String HASH = "#";

  @Nullable
  public static Revision fromString(@Nullable final String stringRevision) throws ParseException {
    return stringRevision == null ? null : fromNotNullString(stringRevision);
  }

  @NotNull
  public static Revision fromNotNullString(@NotNull final String stringRevision) throws ParseException {
    final int hashPos = stringRevision.indexOf(HASH);
    final String stringRevisionWithoutHash = hashPos == -1 ? stringRevision : stringRevision.substring(0, hashPos);
    return FIRST.equals(stringRevisionWithoutHash) ? first() : fromNotNullStringInternal(patchIfNeeded(stringRevisionWithoutHash));
  }

  @NotNull
  private static Revision fromNotNullStringInternal(@NotNull final String stringRevision) throws ParseException {
    final int separatorPos = stringRevision.indexOf(SEPARATOR);
    final Date date = CCCommonParseUtil.parseDate(stringRevision.substring(separatorPos + 1));
    final Long eventId = separatorPos == -1 ? null : CCCommonParseUtil.parseLong(stringRevision.substring(0, separatorPos));
    return new RevisionImpl(eventId, date);
  }

  @NotNull
  private static String patchIfNeeded(@NotNull final String dateString) {
    if (dateString.contains("@")) return dateString;
    final int daySep = dateString.indexOf('-');
    if (daySep <= 2) return dateString;
    try {
      final long eventId = Long.parseLong(dateString.substring(0, daySep - 2)) - '@';
      return eventId + "@" + dateString.substring(daySep - 2);
    }
    catch (final NumberFormatException e) {
      return dateString;
    }
  }

  @NotNull
  public static DateRevision fromChange(@NotNull final ChangeInfo info) {
    return new RevisionImpl(info.getEventId(), info.getDate());
  }

  @NotNull
  public static DateRevision fromDate(@NotNull final Date date) {
    return new RevisionImpl(null, date);
  }

  @NotNull
  public static Revision first() {
    return new FirstRevision();
  }

  @Nullable
  public abstract DateRevision getDateRevision();

  public abstract boolean beforeOrEquals(@NotNull final Revision that);

  public abstract void appendLSHistoryOptions(@NotNull final List<String> optionList);

  @NotNull
  public abstract String asString();

  @NotNull
  public abstract String asDisplayString();

  @NotNull
  public abstract Revision shiftToPast(final int minutes);

  @NotNull
  public String asUniqueString() {
    return asString() + HASH + Dates.now().getTime();
  }

  @NotNull
  @Override
  public String toString() {
    return asString();
  }

  private static class RevisionImpl extends DateRevision {
    @Nullable private final Long myEventId;
    @NotNull private final Date myDate;

    private RevisionImpl(@Nullable final Long eventId, @NotNull final Date date) {
      myEventId = eventId;
      myDate = date;
    }

    @Override
    @NotNull
    public DateRevision getDateRevision() {
      return this;
    }

    @Override
    public boolean beforeOrEquals(@NotNull final Revision _that) {
      if (_that instanceof RevisionImpl) {
        final RevisionImpl that = (RevisionImpl)_that;
        if (myEventId != null && that.myEventId != null) {
          return myEventId <= that.myEventId;
        }
        return myDate.getTime() <= that.myDate.getTime();
      }
      else {
        return !_that.beforeOrEquals(this); // they can't be equal since they have different types
      }
    }

    @Override
    public void appendLSHistoryOptions(@NotNull final List<String> optionList) {
      optionList.add("-since");
      optionList.add(getDateString());
    }

    @NotNull
    @Override
    public Date getDate() {
      return myDate;
    }

    @NotNull
    @Override
    public String asString() {
      return myEventId == null ? getDateString() : myEventId + SEPARATOR + getDateString();
    }

    @NotNull
    @Override
    public String asDisplayString() {
      return getDateString();
    }

    @NotNull
    @Override
    public Revision shiftToPast(final int minutes) {
      return new RevisionImpl(null, Dates.before(myDate, minutes * Dates.ONE_MINUTE));
    }

    @NotNull
    @Override
    public String getDateString() {
      return CCCommonParseUtil.formatDate(myDate);
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) return true;
      if (!(o instanceof RevisionImpl)) return false;

      final RevisionImpl revision = (RevisionImpl)o;

      if (!myDate.equals(revision.myDate)) return false;
      return myEventId == null ? revision.myEventId == null : myEventId.equals(revision.myEventId);
    }

    @Override
    public int hashCode() {
      int result = myEventId != null ? myEventId.hashCode() : 0;
      result = 31 * result + myDate.hashCode();
      return result;
    }
  }

  private static class FirstRevision extends Revision {
    @Nullable
    @Override
    public DateRevision getDateRevision() {
      return null;
    }

    @Override
    public boolean beforeOrEquals(@NotNull final Revision that) {
      return true;
    }

    @Override
    public void appendLSHistoryOptions(@NotNull final List<String> optionList) {}

    @NotNull
    @Override
    public String asString() {
      return FIRST;
    }

    @NotNull
    @Override
    public String asDisplayString() {
      return asString();
    }

    @NotNull
    @Override
    public Revision shiftToPast(final int minutes) {
      return this;
    }

    @Override
    public boolean equals(final Object o) {
      return this == o || o instanceof FirstRevision;
    }

    @Override
    public int hashCode() {
      return 0;
    }
  }
}
