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

import org.jetbrains.annotations.NotNull;

public class CCModificationKey {
  @NotNull private DateRevision myVersion;
  private final String myUser;
  private final String myActivity;
  private final CommentHolder myCommentHolder = new CommentHolder();

  public CCModificationKey(@NotNull final DateRevision version, final String user, final String activity) {
    myVersion = version;
    myUser = user;
    myActivity = activity;
  }

  @NotNull
  public DateRevision getVersion() {
    return myVersion;
  }

  public void setVersion(@NotNull final DateRevision version) {
    myVersion = version;
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

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof CCModificationKey)) return false;

    final CCModificationKey that = (CCModificationKey)o;

    if (myActivity != null ? !myActivity.equals(that.myActivity) : that.myActivity != null) return false;
    if (myUser != null ? !myUser.equals(that.myUser) : that.myUser != null) return false;

    return myVersion.getDate().equals(that.myVersion.getDate());
  }

  @Override
  public int hashCode() {
    int result = myVersion.getDate().hashCode();
    result = 31 * result + (myUser != null ? myUser.hashCode() : 0);
    result = 31 * result + (myActivity != null ? myActivity.hashCode() : 0);
    return result;
  }
}
