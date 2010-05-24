package org.jetbrains.teamcity.vcs.clearcase;

import java.util.ArrayList;

import org.jetbrains.teamcity.vcs.clearcase.CTool.StorageParser;
import org.jetbrains.teamcity.vcs.clearcase.CTool.ViewParser;
import org.jetbrains.teamcity.vcs.clearcase.CTool.VobResultParser;


public class CCRegion {

  private String myTag;

  public CCRegion(){
    this("any");
  }

  public CCRegion(final String tag){
    myTag = tag;
  }

  public String getTag(){
    return myTag;
  }
  
  public CCVob[] getVobs() throws CCException {
    try {
      final ArrayList<CCVob> out = new ArrayList<CCVob>();
      for(VobResultParser result : CTool.lsVob()){
        out.add(new CCVob(result.getTag(), result.getRegion(), result.getServerHost(), result.getGlobalPath()));
      }
      return out.toArray(new CCVob[out.size()]);
      
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
  public CCStorage[] getStorages () throws CCException {
    try {
      final ArrayList<CCStorage> out = new ArrayList<CCStorage>();
      for(StorageParser result : CTool.lsStgLoc()){
        out.add(new CCStorage(result.getServerHost(), result.getTag(), result.getGlobalPath()));
      }
      return out.toArray(new CCStorage[out.size()]);
      
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
  public CCSnapshotView[] getViews () throws CCException {
    try {
      final ArrayList<CCSnapshotView> out = new ArrayList<CCSnapshotView>();
      for(ViewParser result : CTool.lsView()){
        out.add(new CCSnapshotView(result.getRegion(), result.getServerHost(), result.getTag(), result.getGlobalPath()));
      }
      return out.toArray(new CCSnapshotView[out.size()]);
      
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
}
