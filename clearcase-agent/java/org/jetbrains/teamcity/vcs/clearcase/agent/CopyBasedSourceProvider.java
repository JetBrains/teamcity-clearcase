package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsRoot;

import org.apache.log4j.Logger;
import org.jetbrains.teamcity.vcs.clearcase.CCDelta;
import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;

import ch.fhnw.filecopier.CopyJob;
import ch.fhnw.filecopier.FileCopier;
import ch.fhnw.filecopier.Source;

class CopyBasedSourceProvider extends AbstractSourceProvider {
  
  static final Logger LOG = Logger.getLogger(LinkBasedSourceProvider.class);
  
  CopyBasedSourceProvider(BuildAgentConfiguration config, VcsRoot root, CheckoutRules rule, String version, File checkoutRoot, BuildProgressLogger logger){
    super(config, root, rule, version, checkoutRoot, logger);
  }
  
  protected File getCCRootDirectory (File checkoutRoot) {
    final File ccCheckoutRoot = new File(myAgentConfig.getTempDirectory(), "snapshots");
    ccCheckoutRoot.mkdirs();
    return ccCheckoutRoot;
  }
  

  public void publish(CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException {
    ifCreated(ccview, publishTo, pathWithinView, logger);
    final int prefixLength = FileUtil.normalizeRelativePath(pathWithinView).length();
    if(changes != null && changes.length>0){
      final ArrayList<CopyJob> jobs = new ArrayList<CopyJob>();
      for(CCDelta change : changes){
        LOG.debug(String.format("\"%s\" change detected", change));
        //TODO:
        final String pathInView = change.getPath();
        final String targetPath = pathInView.substring(prefixLength, pathInView.length());
        if (CCDelta.Kind.ADDITION == change.getKind() 
            || CCDelta.Kind.MODIFICATION == change.getKind()) {
          final String targetParentPath = new File(targetPath).getParent();
          final String sourceFilePath = new File(ccview.getLocalPath().getAbsolutePath(), change.getPath()).getAbsolutePath();
          jobs.add(new CopyJob(new Source[]{new Source(sourceFilePath)}, new String[] {targetParentPath}));
          LOG.debug(String.format("Schedule copying of \"%s\" to \"%s\"", sourceFilePath, targetParentPath));
          
        } else if (CCDelta.Kind.DELETION == change.getKind()){
          final File fileForDeletion = new File(publishTo, targetPath);
          LOG.debug(String.format("Will delete \"%s\"", fileForDeletion));
          fileForDeletion.delete();
          
        } else {
          LOG.error(String.format("Unsupported change type: \"%s\"", change));
        }
      }
      if(!jobs.isEmpty()){
        new FileCopier().copy(jobs.toArray(new CopyJob[jobs.size()]));
      }
      
    } else {
      LOG.debug("Up to date");
    }
  }
  
  /**
   * Performs initial copying to checkout directory 
   */
  private void ifCreated(CCSnapshotView ccview, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException {
    final File viewsPath = new File(ccview.getLocalPath(), pathWithinView);
    if(viewsPath.exists()){
      if(publishTo.listFiles().length == 0){//compare contents. can be non empty(others roots already checked out)
        LOG.debug(String.format("The destination folder \"%s\" is empty. Starting initial copying", publishTo));
        final ArrayList<Source> sources = new ArrayList<Source>();
        for(File child : viewsPath.listFiles()){
          sources.add(new Source(child.getAbsolutePath()));
          LOG.debug(String.format("Will copy \"%s\" to \"%s\"", child, publishTo));
        }
        new FileCopier().copy(new CopyJob(sources.toArray(new Source[sources.size()]), new String[] {publishTo.getAbsolutePath()}));
      }
    } else {
      LOG.info(String.format("Could not create initial structure: path \"%s\" does not exist", viewsPath));
    }
    
  }
  
}