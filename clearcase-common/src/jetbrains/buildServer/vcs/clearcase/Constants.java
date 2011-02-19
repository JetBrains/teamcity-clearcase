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
package jetbrains.buildServer.vcs.clearcase;

import org.jetbrains.annotations.NonNls;

public interface Constants {
  
  @NonNls public static final String NAME = "clearcase";
  @NonNls public static final String VIEW_TAG = "view-tag";  
  @NonNls public static final String VIEW_PATH = "view-path";
  @NonNls public static final String CC_VIEW_PATH = "cc-view-path";
  @NonNls public static final String RELATIVE_PATH = "rel-path";
  @NonNls public static final String TYPE = "TYPE";
  @NonNls public static final String UCM = "UCM";
  @NonNls public static final String BASE = "BASE";
  @NonNls public static final String GLOBAL_LABELS_VOB = "global-labels-vob";
  @NonNls public static final String USE_GLOBAL_LABEL = "use-global-label";
  @NonNls public static final String LSHISTORY_DEFAULT_OPTIONS = "clearcase.lshistory.options.default";
  @NonNls public static final String LSHISTORY_VCS_ROOT_OPTIONS_BY_ID = "clearcase.lshistory.options.vcsRoot{%d}";
  @NonNls public static final String LSHISTORY_UCM_DELAY = "clearcase.lshistory.ucm.delay.seconds";

  public static final String VOBS_NAME_ONLY = "vobs";
  public static final String VOBS = "vobs/";
  public static final String MAIN = "main";
  
  public static final String AGENT_CONFIGSPECS_SYS_PROP_PATTERN = "vcs.clearcase.configspec.%s";
  public static final String AGENT_SOURCE_VIEW_TAG_PROP_PATTERN = "vcs.clearcase.view.tag.%s";
  
  public static final String AGENT_DISABLE_VALIDATION_ERRORS = "clearcase.agent.checkout.disable.validation.errors"; //$NON-NLS-1$  
  public static final String TREAT_MAIN_AS_VERSION_IDENTIFIER = "clearcase.treat.main.as.version.identifier";
  public static final String DISABLE_HISTORY_ELEMENT_TRANSFORMATION = "clearcase.disable.history.transformation";
}
