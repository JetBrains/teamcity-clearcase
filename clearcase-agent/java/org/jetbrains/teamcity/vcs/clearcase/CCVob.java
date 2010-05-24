package org.jetbrains.teamcity.vcs.clearcase;

import java.io.File;

import org.jetbrains.teamcity.vcs.clearcase.CTool.VobObjectResult;


public class CCVob {
  
  private String myTag;
  private String myGlobalPath;
  private boolean isAvailable;
  private String myRegion;
  private String myServerHost;

  public CCVob(final String tag){
    myTag = tag;
  }
  
  CCVob(final String tag, final String region, final String host, final String globalPath){
    myTag = tag;
    myRegion = region;
    myServerHost = host;
    myGlobalPath = globalPath;
  }
  
  public String getTag() {
    return myTag;
  }
  
  public String getRegion() {
    return myRegion;
  }
  
  public String getServerHost() {
    return myServerHost;
  }

  public String getGlobalPath() {
    return myGlobalPath;
  }
  
  @Override
  public String toString () {
    return String.format("{region=\"%s\", host=\"%s\", tag=\"%s\", global=\"%s\"}", getRegion(), getServerHost(), getTag(), getGlobalPath()); 
  }
  
  public void create(final String reason) throws CCException {
    try {
      final VobObjectResult result = CTool.createVob(myTag, reason);
      myGlobalPath =  result.getGlobalPath();
      isAvailable = true;
    } catch (Exception e) {
      throw new CCException(e);
    }
    
  }
  
  public void load(final File dump, String reason) throws CCException {
    try {
      final VobObjectResult result = CTool.importVob(myTag, dump, reason);
      myGlobalPath = result.getGlobalPath();

    } catch (Exception e) {
      throw new CCException(e);

    }
  }
  
  public void drop() throws CCException {
    try {
      if(isAvailable()){
        CTool.dropVob(getGlobalPath());
        isAvailable  = false;
      }
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  public boolean isAvailable() {
    return isAvailable;
  }

}
