package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;
import java.io.IOException;

import jetbrains.buildServer.agent.BuildProgressLogger;

import org.apache.log4j.Logger;
import org.jetbrains.teamcity.vcs.clearcase.CCDelta;
import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;
import org.jetbrains.teamcity.vcs.clearcase.Util;

class LinkBasedPublisher implements IChangePublisher {
  
  static final Logger LOG = Logger.getLogger(LinkBasedPublisher.class);  
  
  private boolean isWindows;

  LinkBasedPublisher(){
    isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
  }
  
  private boolean isWindows(){
    return isWindows;
  }

  public void publish(CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException, InterruptedException {
    clear(publishTo);//TODO: !!! it's unsafe cleaning. it's possible deletion of other's root data!!!!
    ifCreated(ccview, publishTo, pathWithinView, logger);
  }
  
  private void clear(File publishTo) {
    for(File element : publishTo.listFiles()){
      LOG.debug(String.format("Deleting \"%s\"", element));
      element.delete();
    }
  }

  /**
   * Performs initial copying to checkout directory 
   * @throws InterruptedException 
   */
  private void ifCreated(CCSnapshotView ccview, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException, InterruptedException {
    final File viewsPath = new File(ccview.getLocalPath(), pathWithinView);
    if(viewsPath.exists()){
      for(File file : viewsPath.listFiles()){
        if(!new File(publishTo, file.getName()).exists()){
          LOG.info(String.format("Restore missing link from \"%s\" to \"%s\"", file, publishTo));
          ln(file, publishTo);
        }
      }
    } else {
      LOG.info(String.format("Could not create initial structure: path \"%s\" does not exist", viewsPath));
    }
  }

  private void ln(File file, File publishTo) throws IOException, InterruptedException {
    LOG.debug(String.format("Creating link from \"%s\" to \"%s\"", file, publishTo));      
    final String command;
    if(isWindows()){
      if(file.isDirectory()){
        command = String.format("cmd /c mklink /D %s\\%s %s", publishTo.getAbsolutePath(), file.getName(), file.getAbsolutePath());
      } else {
        command = String.format("cmd /c mklink %s\\%s %s", publishTo.getAbsolutePath(), file.getName(), file.getAbsolutePath());
      }
    } else {
      command = String.format("ln -s %s %s/%s", file.getAbsolutePath(), publishTo.getAbsolutePath(), file.getName());
    }
    Util.execAndWait(command);      
  }
  
}