package jetbrains.buildServer.vcs.clearcase.agent;
//package org.jetbrains.teamcity.vcs.clearcase.agent;
//
//import java.io.File;
//import java.io.IOException;
//
//import jetbrains.buildServer.agent.BuildAgentConfiguration;
//import jetbrains.buildServer.agent.BuildProgressLogger;
//import jetbrains.buildServer.vcs.CheckoutRules;
//import jetbrains.buildServer.vcs.VcsRoot;
//
//import org.apache.log4j.Logger;
//import org.jetbrains.teamcity.vcs.clearcase.CCDelta;
//import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;
//import org.jetbrains.teamcity.vcs.clearcase.Util;
//
//class LinkBasedSourceProvider extends AbstractSourceProvider {
//  
//  static final Logger LOG = Logger.getLogger(LinkBasedSourceProvider.class);  
//  
//  private boolean isWindows;
//
//  LinkBasedSourceProvider(BuildAgentConfiguration config, VcsRoot root, CheckoutRules rule, String version, File checkoutRoot, BuildProgressLogger logger){
//    super(config, root, rule, version, checkoutRoot, logger);
//    isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
//  }
//  
//  private boolean isWindows(){
//    return isWindows;
//  }
//  
//  protected File getCCRootDirectory (File checkoutRoot) {
//    final File ccCheckoutRoot = new File(myAgentConfig.getTempDirectory(), "snapshots");
//    ccCheckoutRoot.mkdirs();
//    return ccCheckoutRoot;
//  }
//
//  public void publish(CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException, InterruptedException {
//    clear(publishTo);//TODO: !!! it's unsafe cleaning. it's possible deletion of other's root data!!!!
//    ifCreated(ccview, publishTo, pathWithinView, logger);
//  }
//  
//  private void clear(File publishTo) {
//    for(File element : publishTo.listFiles()){
//      LOG.debug(String.format("Deleting \"%s\"", element));
//      element.delete();
//    }
//  }
//
//  /**
//   * Performs initial copying to checkout directory 
//   * @throws InterruptedException 
//   */
//  private void ifCreated(CCSnapshotView ccview, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException, InterruptedException {
//    final File viewsPath = new File(ccview.getLocalPath(), pathWithinView);
//    if(viewsPath.exists()){
//      for(File file : viewsPath.listFiles()){
//        if(!new File(publishTo, file.getName()).exists()){
//          LOG.info(String.format("Restore missing link from \"%s\" to \"%s\"", file, publishTo));
//          ln(file, publishTo);
//        }
//      }
//    } else {
//      LOG.info(String.format("Could not create initial structure: path \"%s\" does not exist", viewsPath));
//    }
//  }
//
//  private void ln(File file, File publishTo) throws IOException, InterruptedException {
//    LOG.debug(String.format("Creating link from \"%s\" to \"%s\"", file, publishTo));      
//    final String command;
//    if(isWindows()){
//      if(file.isDirectory()){
//        command = String.format("cmd /c mklink /D %s\\%s %s", publishTo.getAbsolutePath(), file.getName(), file.getAbsolutePath());
//      } else {
//        command = String.format("cmd /c mklink %s\\%s %s", publishTo.getAbsolutePath(), file.getName(), file.getAbsolutePath());
//      }
//    } else {
//      command = String.format("ln -s %s %s/%s", file.getAbsolutePath(), publishTo.getAbsolutePath(), file.getName());
//    }
//    Util.execAndWait(command);      
//  }
//  
//}