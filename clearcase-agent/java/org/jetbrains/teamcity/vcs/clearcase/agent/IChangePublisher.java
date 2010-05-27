package org.jetbrains.teamcity.vcs.clearcase.agent;

import java.io.File;
import java.io.IOException;

import jetbrains.buildServer.agent.BuildProgressLogger;

import org.jetbrains.teamcity.vcs.clearcase.CCDelta;
import org.jetbrains.teamcity.vcs.clearcase.CCSnapshotView;

interface IChangePublisher {
  void publish(CCSnapshotView ccview, CCDelta[] changes, File publishTo, String pathWithinView, BuildProgressLogger logger) throws IOException, InterruptedException;
}