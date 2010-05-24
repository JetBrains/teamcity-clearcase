package org.jetbrains.teamcity.vcs.clearcase;

public class CCStorage {

  private String myTag;
  private String myGlobalPath;
  private String myServerHost;

  CCStorage(final String serverHost, final String tag, final String globalPath){
      myTag = tag;
      myGlobalPath = globalPath;
      myServerHost = serverHost;
  }
  
  public String getServerHost() {
    return myServerHost;
  }

  public String getTag() {
    return myTag;
  }

  public String getGlobalPath() {
    return myGlobalPath;
  }
  
}
