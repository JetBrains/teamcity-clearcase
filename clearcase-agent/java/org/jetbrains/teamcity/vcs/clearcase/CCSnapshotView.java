package org.jetbrains.teamcity.vcs.clearcase;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.log4j.Logger;
import org.jetbrains.teamcity.vcs.clearcase.CTool.ChangeParser;
import org.jetbrains.teamcity.vcs.clearcase.CTool.VobObjectResult;

public class CCSnapshotView {
  
  private static final Logger LOG = Logger.getLogger(CTool.class);  

  private ArrayList<String> myConfigSpecs = new ArrayList<String>();
  private File myLocalPath;
  private String myGlobalPath;
  private String myTag;
  private boolean isAvailable;

  /**
  mkview -snapshot [-tag snapshot-view-tag]
                    [-tcomment tag-comment] [-tmode text-mode]
                    [-cachesize size] [-ptime] [-stream stream-selector]
                    [ -stgloc view-stgloc-name
                    | -colocated_server [-host hostname -hpath host-snapshot-view-pname -gpath global-snapshot-view-pname]
                    | -vws view-storage-pname [-host hostname -hpath host-stg-pname -gpath global-stg-pname]
                    ] snapshot-view-pname  
  */

  
  CCSnapshotView (
      final String region,
      final String server,
      final String tag,
      final String glolbalPath){
    
    myTag = tag;
    myGlobalPath = glolbalPath;
    isAvailable = true;
  }
  
  public CCSnapshotView (
      final String tag,
      final String localPath){
    
    myTag = tag;
    myLocalPath = new File(localPath);
  }
  
  public CCSnapshotView(
      final CCVob vob,
      final String tag,
      final String localPath){
    
    myTag = tag;
    myConfigSpecs = new ArrayList<String>(CTool.DEFAULT_CONFIG_SPECS);
    myConfigSpecs.add(Util.createLoadRuleForVob(vob));
    myLocalPath = new File(localPath);
    myLocalPath.getParentFile().mkdirs();
  }
  
  
  public String getTag(){
    return myTag;
  }
  
  public String getGlobalPath(){
    return myGlobalPath;
  }
  
  public File getLocalPath(){
    return myLocalPath;
  }
  
  public void create(String reason) throws CCException {
    try {
      if(exists()){
        throw new CCException(String.format("The view \"%s\" already exists", getTag()));
      }
      final VobObjectResult result = CTool.createSnapshotView(getTag(), myLocalPath, reason);
      myGlobalPath =  result.getGlobalPath();
      isAvailable = true;
      setConfigSpec(myConfigSpecs);
      
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
  public void setConfigSpec(final List<String> configSpec) throws CCException {
    try{
      myConfigSpecs = new ArrayList<String>(configSpec);
      CTool.setConfigSpecs(myLocalPath, myConfigSpecs);
      
    } catch (Exception e) {
      throw new CCException(e);
    }
    
  }
  
  public List<String> getConfigSpec() throws CCException {
    try {
      myConfigSpecs = new ArrayList<String>(CTool.getConfigSpecs(getTag()));
      return myConfigSpecs;
      
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
  public void add(File file, String reason) throws CCException {
    try {
      //TODO: check the File inside the View
      CTool.checkout(myLocalPath, file.getParentFile(), reason);
      CTool.mkelem(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file.getParentFile(), reason);
    } catch (Exception e){
      throw new CCException(e);
    }
  }
  
  public void checkout(File file, String reason) throws CCException {
    try {
      //TODO: check the File inside the View
      CTool.checkout(myLocalPath, file, reason);
    } catch (Exception e){
      throw new CCException(e);
    }
  }
  
  public void checkin(File file, String reason) throws CCException {
    try {
      //TODO: check the File inside the View
      CTool.checkin(myLocalPath, file, reason);
    } catch (Exception e){
      throw new CCException(e);
    }
  }
  
  public void remove(File file, String reason) throws CCException {
    try {
      //TODO: check the File inside the View
      CTool.checkout(myLocalPath, file.getParentFile(), reason);
      CTool.rmname(myLocalPath, file, reason);
      CTool.checkin(myLocalPath, file.getParentFile(), reason);
    } catch (Exception e){
      throw new CCException(e);
    }
  }
  
  
  public CCDelta[] update(File file) throws CCException {
    return update(file, new Date());
  }
  
  public CCDelta[] update(File file, final Date to) throws CCException {
    try{
      final ChangeParser[] rawChanges = CTool.update(file, to);
      final ArrayList<CCDelta> out = new ArrayList<CCDelta>(rawChanges.length);
      for(final ChangeParser change : rawChanges){
        out.add(new CCDelta(this, change.isAddition(), change.isChange(), change.isDeletion(), change.getLocalPath(), change.getRevisionBefor(), change.getRevisionAfter()));
      }
      return out.toArray(new CCDelta[out.size()]);
    } catch (Exception e){
      throw new CCException(e);
    }
  }
  
  
  public boolean exists() throws CCException {
    final CCRegion region = new CCRegion();
    for(CCSnapshotView view : region.getViews()){
      if(view.getTag().trim().equals(getTag().trim())){
        return true;
      }
    }
    return false;
  }
  
  public boolean isAlive() throws CCException {
    try{
      CTool.lsView(getLocalPath());
      return true;
    } catch (Exception e) {
      LOG.debug(e);
      return false;  
    }
  }
  
  public void drop() throws CCException {
    try {
      if(isAvailable()){
        CTool.dropView(getGlobalPath());
        isAvailable = false;
      }
    } catch (Exception e) {
      throw new CCException(e);
    }
  }
  
  public boolean isAvailable() {
    return isAvailable;
  }
  
  @Override
  public String toString () {
    return String.format("{CCSnapshotView: tag=\"%s\", global=\"%s\"}", getTag(), getGlobalPath()); 
  }
  
}
