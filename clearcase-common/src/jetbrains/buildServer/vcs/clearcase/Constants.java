/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package jetbrains.buildServer.vcs.clearcase;

import org.jetbrains.annotations.NonNls;

public interface Constants {

  @NonNls
  public static final String NAME = "clearcase";
  @NonNls
  public static final String VIEW_TAG = "view-tag"; //$NON-NLS-1$  
  @NonNls
  public static final String VIEW_PATH = "view-path"; //$NON-NLS-1$
  @NonNls
  public static final String CC_VIEW_PATH = "cc-view-path"; //$NON-NLS-1$
  @NonNls
  public static final String RELATIVE_PATH = "rel-path"; //$NON-NLS-1$
  @NonNls
  public static final String BRANCH_PROVIDER = "branch-provider"; //$NON-NLS-1$
  @NonNls
  public static final String BRANCH_PROVIDER_AUTO = "auto"; //$NON-NLS-1$
  @NonNls
  public static final String BRANCH_PROVIDER_CUSTOM = "custom"; //$NON-NLS-1$
  @NonNls
  public static final String BRANCHES = "branches"; //$NON-NLS-1$
  @NonNls
  public static final String TYPE = "TYPE"; //$NON-NLS-1$
  @NonNls
  public static final String UCM = "UCM"; //$NON-NLS-1$
  @NonNls
  public static final String BASE = "BASE"; //$NON-NLS-1$
  @NonNls
  public static final String GLOBAL_LABELS_VOB = "global-labels-vob"; //$NON-NLS-1$
  @NonNls
  public static final String USE_GLOBAL_LABEL = "use-global-label"; //$NON-NLS-1$

  @NonNls
  public static final String VOBS_NAME_ONLY = "vobs"; //$NON-NLS-1$
  @NonNls
  public static final String VOBS = "vobs/"; //$NON-NLS-1$
  @NonNls
  public static final String MAIN = "main"; //$NON-NLS-1$
  @NonNls
  public static final String EMPTY = ""; //$NON-NLS-1$

  @NonNls
  public static final String AGENT_CONFIGSPECS_SYS_PROP_PATTERN = "vcs.clearcase.configspec.%s"; //$NON-NLS-1$
  @NonNls
  public static final String AGENT_SOURCE_VIEW_TAG_PROP_PATTERN = "vcs.clearcase.view.tag.%s"; //$NON-NLS-1$

  @NonNls
  public static final String TEAMCITY_PROPERTY_LSHISTORY_DEFAULT_OPTIONS = "clearcase.lshistory.options.default"; //$NON-NLS-1$
  @NonNls
  public static final String TEAMCITY_PROPERTY_LSHISTORY_VCS_ROOT_OPTIONS_BY_ID = "clearcase.lshistory.options.vcsRoot{%d}"; //$NON-NLS-1$
  @NonNls
  public static final String TEAMCITY_PROPERTY_LSHISTORY_UCM_DELAY = "clearcase.lshistory.ucm.delay.seconds"; //$NON-NLS-1$
  @NonNls
  public static final String TEAMCITY_PROPERTY_AGENT_DISABLE_VALIDATION_ERRORS = "clearcase.agent.checkout.disable.validation.errors"; //$NON-NLS-1$  
  @NonNls
  public static final String TEAMCITY_PROPERTY_DO_NOT_TREAT_MAIN_AS_VERSION_IDENTIFIER = "clearcase.do.not.treat.main.as.version.identifier"; //$NON-NLS-1$
  @NonNls
  public static final String TEAMCITY_PROPERTY_IGNORE_ERROR_PATTERN = "clearcase.ignore.error.pattern"; //$NON-NLS-1$

  @NonNls
  public static final String CLEARTOOL_CHECK_AVAILABLE_COMMAND = "cleartool hostinfo"; //$NON-NLS-1$

  public static String CLIENT_NOT_FOUND_MESSAGE = "Cannot run \"cleartool\": the executable cannot be found. Please ensure the ClearCase client is installed on the TeamCity server and \"cleartool\" is present in the PATH.";
}
