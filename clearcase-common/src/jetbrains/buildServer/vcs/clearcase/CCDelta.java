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
package jetbrains.buildServer.vcs.clearcase;


public class CCDelta {
  
  private final CCSnapshotView myView;
  
  private final Kind myKind;
  
  private final String myPath;

  private final String myRevBefor;

  private final String myRevAfter;
  
  
  CCDelta(CCSnapshotView view, boolean isAddition, boolean isChange, boolean isDeletion, String path, String revBefor, String revAfter){
    myView = view;
    //kind
    if(isAddition){
      myKind = Kind.ADDITION;
    } else if (isDeletion){
      myKind = Kind.DELETION;
    } else {
      myKind = Kind.MODIFICATION;
    }
    //resource
    myPath = path;
    //revisions 
    myRevBefor = revBefor;
    myRevAfter = revAfter;
    
  }


  /**
   * 
   * @return View the change belongs to 
   */
  public CCSnapshotView getView() {
    return myView;
  }


  public Kind getKind() {
    return myKind;
  }

  /**
   * 
   * @return A file within the View
   */
  public String getPath() {
    return myPath;
  }


  public String getRevisionBefor() {
    return myRevBefor;
  }


  public String getRevisionAfter() {
    return myRevAfter;
  }
  
  @Override
  public String toString() {
    return String.format("{CCChange: kind=%s, path=\"%s\", befor=%s, after=%s}", getKind(), getPath(), getRevisionBefor(), getRevisionAfter());
  }
  
  
}
