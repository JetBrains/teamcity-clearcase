/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.vcs.UpdateByCheckoutRules2;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsRoot;
import jetbrains.buildServer.vcs.clearcase.CCException;

import org.jetbrains.annotations.NotNull;


interface ISourceProvider extends UpdateByCheckoutRules2 {
  
  String[] getConfigSpecs(final @NotNull AgentRunningBuild build, final @NotNull VcsRoot root) throws CCException; 
  
//  void publish(final @NotNull AgentRunningBuild build, final @NotNull CCSnapshotView ccview, final @NotNull CCDelta[] changes, final @NotNull File publishTo, final @NotNull String pathWithinView, final @NotNull BuildProgressLogger logger) throws CCException;
  
  void validate(final @NotNull File checkoutRoot, final @NotNull VcsRoot vcsRoot, final @NotNull CheckoutRules rules) throws VcsValidationException;
  
}