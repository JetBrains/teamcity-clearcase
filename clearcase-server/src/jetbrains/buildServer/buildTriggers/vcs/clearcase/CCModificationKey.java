/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

public class CCModificationKey {
  private final String myDate;
  private final String myUser;
  private final String myActivity;
  private final CommentHolder myCommentHolder = new CommentHolder();

  public CCModificationKey(final String date, final String user, final String activity) {
    myDate = date;
    myUser = user;
    myActivity = activity;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof CCModificationKey)) return false;

    final CCModificationKey that = (CCModificationKey)o;

    if (myActivity != null ? !myActivity.equals(that.myActivity) : that.myActivity != null) return false;
    if (myDate != null ? !myDate.equals(that.myDate) : that.myDate != null) return false;
    if (myUser != null ? !myUser.equals(that.myUser) : that.myUser != null) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myDate != null ? myDate.hashCode() : 0;
    result = 31 * result + (myUser != null ? myUser.hashCode() : 0);
    result = 31 * result + (myActivity != null ? myActivity.hashCode() : 0);
    return result;
  }

  public String getDate() {
    return myDate;
  }

  public String getUser() {
    return myUser;
  }

  public String getActivity() {
    return myActivity;
  }

  public CommentHolder getCommentHolder() {
    return myCommentHolder;
  }
}
