/*
 * Copyright 2000-2018 JetBrains s.r.o.
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
package jetbrains.buildServer.vcs.clearcase.agent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.VcsRootEntry;
import jetbrains.buildServer.vcs.clearcase.*;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public class LinkBasedSourceProvider extends AbstractSourceProvider {

  private static final HashMap<String, Collection<PublishingRequest>> requestsMap = new HashMap<String, Collection<PublishingRequest>>();

  static final Logger LOG = Logger.getLogger(LinkBasedSourceProvider.class);

  private final boolean isWindows;

  public LinkBasedSourceProvider() {
    isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
  }

  private boolean isWindows() {
    return isWindows;
  }

  public void publish(AgentRunningBuild build, CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws CCException {
    //try {
      //schedule publish
      Collection<PublishingRequest> requests = requestsMap.get(build.getBuildTypeId());
      if (requests == null) {
        requests = new ArrayList<PublishingRequest>();
        requestsMap.put(build.getBuildTypeId(), requests);
      }
      final PublishingRequest request = new PublishingRequest(build, ccview, changes, publishTo, pathWithinView);
      requests.add(request);
      LOG.debug(String.format("Publishing scheduled: \"%s\"", request));
      //check there is already all roots collected and fire publishing if so
      final Collection<PublishingRequest> scheduledRequests = requestsMap.get(build.getBuildTypeId());
      if (getCCRootsCount(build) == scheduledRequests.size()) {
        try {
          System.err.println("the last");
          //          createLinks(ccview, absoluteLinkSource, linkTargetOwnerDirectory, linkBuffer);

        } finally {
          requestsMap.remove(build.getBuildTypeId());
          LOG.debug("Empty publishing requiets queue");
        }
      }

      //      counter
      //      LOG.info(String.format("Publish: view=\"%s\", publishTo=\"%s\", pathWithinView=\"%s\"", ccview, publishTo, pathWithinView));
      //      discardLinks(ccview);
      //      publishLinks(ccview, publishTo, pathWithinView, logger);
    //} finally {

      //      if(counter == build.get)
    //}
  }

  private int getCCRootsCount(AgentRunningBuild build) {
    int count = 0;
    for (VcsRootEntry entry : build.getVcsRootEntries()) {
      if (Constants.NAME.equals(entry.getVcsRoot().getVcsName())) {
        count++;
      }
    }
    return count;
  }

  void discardLinks(CCSnapshotView ccview) throws CCException {
    for (File element : getCreatedLinks(ccview)) {
      LOG.debug(String.format("Deleting \"%s\"", element));
      element.delete();
    }
    discardStorage(ccview);
  }

  File[] getCreatedLinks(CCSnapshotView ccview) throws CCException {
    try {
      final ArrayList<File> links = new ArrayList<File>();
      final File linksStorageFile = getLinksStorageFile(ccview);
      if (linksStorageFile.exists()) {
        for (String absolutePath : FileUtil.readFile(linksStorageFile)) {
          final File link = new File(absolutePath);
          if (link.exists()) {
            links.add(link);
          }
        }
      }
      return links.toArray(new File[links.size()]);

    } catch (IOException e) {
      throw new CCException(e);
    }
  }

  /**
   * Performs initial copying to checkout directory
   * 
   * @throws InterruptedException
   */
  File[] publishLinks(CCSnapshotView ccview, File publishTo, String pathWithinView, BuildProgressLogger logger) throws CCException {
    final ArrayList<File> linksBuffer = new ArrayList<File>();
    final File viewSourceRoot = new File(ccview.getLocalPath(), pathWithinView.trim());
    if (viewSourceRoot.exists()) {
      for (final File viewSourceEntry : viewSourceRoot.listFiles()) {// it's wrong
        createLinks(ccview, viewSourceEntry, publishTo, linksBuffer);
      }
    } else {
      LOG.info(String.format("Could not create initial structure: path \"%s\" does not exist", viewSourceRoot));
    }
    return linksBuffer.toArray(new File[linksBuffer.size()]);
  }

  private void createLinks(CCSnapshotView ccview, File absoluteLinkSource, File linkTargetOwnerDirectory, Collection<File> linkBuffer) throws CCException {
    final File linkTarget = new File(linkTargetOwnerDirectory.getAbsolutePath(), absoluteLinkSource.getName());
    if (linkTarget.exists() && linkTarget.isDirectory()) {// have to create link to each entry as far target can be created by other vcs root
      LOG.info(String.format("Target \"%s\" already exists. Create links for all children reqursive", linkTarget));
      for (File linkSourceChild : absoluteLinkSource.listFiles()) {
        createLinks(ccview, linkSourceChild, linkTarget, linkBuffer);
      }
      return;
    }
    linkBuffer.add(ln(ccview, absoluteLinkSource, linkTarget.getParentFile()));
  }

  File ln(final CCSnapshotView ccview, final File absoluteLinkSource, final File linkTargetOwnerDirectory) throws CCException {
    try {
      if (!linkTargetOwnerDirectory.exists()) {
        linkTargetOwnerDirectory.mkdirs();
      }
      final File linkTarget = new File(linkTargetOwnerDirectory.getAbsolutePath(), absoluteLinkSource.getName());
      final String command;
      if (isWindows()) {
        if (absoluteLinkSource.isDirectory()) {
          command = String.format("cmd /c mklink /D %s %s", linkTarget.getAbsolutePath(), absoluteLinkSource.getAbsolutePath());
        } else {
          command = String.format("cmd /c mklink %s %s", linkTarget.getAbsolutePath(), absoluteLinkSource.getAbsolutePath());
        }
      } else {
        if (absoluteLinkSource.isDirectory()) {
          command = String.format("ln -s %s %s", absoluteLinkSource.getAbsolutePath(), linkTarget.getAbsolutePath());
        } else {
          command = String.format("ln -s %s %s", absoluteLinkSource.getAbsolutePath(), linkTarget.getAbsolutePath());
        }
      }
      Util.execAndWait(command);
      store(ccview, linkTarget);
      return linkTarget;
    } catch (Exception e) {
      throw new CCException(e);
    }
  }

  void discardStorage(final CCSnapshotView ccview) throws CCException {
    try {
      FileUtil.writeToFile(getLinksStorageFile(ccview), "".getBytes(), false);
    } catch (IOException e) {
      throw new CCException(e);
    }
  }

  void store(final CCSnapshotView ccview, final File linkTarget) throws CCException {
    try {
      FileUtil.writeToFile(getLinksStorageFile(ccview), String.format("%s\n", linkTarget.getAbsolutePath()).getBytes(), true);
    } catch (IOException e) {
      throw new CCException(e);
    }
  }

  File getLinksStorageFile(final CCSnapshotView ccview) {
    return new File(ccview.getLocalPath(), ".links");
  }

  @Override
  protected File getCCRootDirectory(AgentRunningBuild build, final @NotNull VcsRoot vcsRoot, final @NotNull File checkoutRoot, final @NotNull CheckoutRules rules) {
    return new File(build.getAgentTempDirectory(), build.getBuildTypeId());
  }

  private static class PublishingRequest {

    private final AgentRunningBuild build;
    private final CCSnapshotView view;
    private final CCDelta[] changes;
    private final File publishTo;
    private final String pathWithinView;

    PublishingRequest(AgentRunningBuild build, CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView) {
      this.build = build;
      this.view = ccview;
      this.changes = changes;
      this.publishTo = publishTo;
      this.pathWithinView = pathWithinView;
    }

    @Override
    public String toString() {
      return String.format("%s::%s: view=\"%s\", publishTo=\"%s\", pathWithinView=\"%s\"", build.getBuildTypeId(), build.getBuildId(), view, publishTo, pathWithinView);
    }
  }

}
