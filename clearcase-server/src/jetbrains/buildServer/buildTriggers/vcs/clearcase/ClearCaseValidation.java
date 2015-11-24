/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import java.io.File;
import java.io.IOException;
import java.util.*;
import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.parameters.ReferencesResolverUtil;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;
import org.apache.log4j.Logger;

public class ClearCaseValidation {

  private static final Logger LOG = Logger.getLogger(ClearCaseValidation.class);

  private static final String VALIDATION_PASSED = "validation passed"; //$NON-NLS-1$
  private static final String VALIDATION_FAILED = "validation failed"; //$NON-NLS-1$
  private static final String SINGLE_PARAM_VALIDATION_FAILED = "validation: \"%s\" failed"; //$NON-NLS-1$
  private static final String SINGLE_PARAM_VALIDATION_PASSED = "validation: \"%s\" passed"; //$NON-NLS-1$
  private static final String DOUBLLE_PARAM_VALIDATION_PASSED = "validation: %s, \"%s\" passed"; //$NON-NLS-1$
  private static final String DOUBLLE_PARAM_VALIDATION_FAILED = "validation: %s, \"%s\" failed"; //$NON-NLS-1$

  interface IValidation {

    String getDescription();

    boolean validate(final Map<String, String> properties, final Collection<InvalidProperty> validationResultBuffer);

  }

  static class ValidationComposite {

    private IValidation[] myValidators = new IValidation[0];

    ValidationComposite(IValidation... validators) {
      if (validators != null) {
        myValidators = validators;
      }
    }

    public Map<IValidation, Collection<InvalidProperty>> validate(Map<String, String> properties) {
      final HashMap<IValidation, Collection<InvalidProperty>> result = new HashMap<IValidation, Collection<InvalidProperty>>();

      for (IValidation validator : myValidators) {
        Collection<InvalidProperty> invalidProps = result.get(validator);
        if (invalidProps == null) {
          invalidProps = new ArrayList<InvalidProperty>();
        }
        boolean validated = validator.validate(properties, invalidProps);
        if (!invalidProps.isEmpty()) {
          result.put(validator, invalidProps);
        }
        if (!validated) {
          break;
        }
      }
      return result;
    }
  }

  /**
   * Checks the "cleartool" runnable
   */
  static class CleartoolValidator implements IValidation {

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      //validate executable
      if (new ClearCaseSupport().isClearCaseClientNotFound()) {
        validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, Constants.CLIENT_NOT_FOUND_MESSAGE));
        debug(String.format(VALIDATION_FAILED, getClass().getSimpleName()));
        return false;
      }
      debug(String.format(VALIDATION_PASSED, getClass().getSimpleName()));
      return true;
    }

    public String getDescription() {
      return "Check the \"cleartool\" runnable"; //$NON-NLS-1$
    }
  }

  /**
   * Checks ClearCase is configured properly
   */
  static class ClearcaseConfigurationValidator implements IValidation {

    static final String UNKNOWN_HOST_PATTERN = "Unknown host"; //$NON-NLS-1$
    static final String CANNOT_CONTACT_LICENSE_SERVER_PATTERN = "Unable to contact albd_server on host"; //$NON-NLS-1$

    public String getDescription() {
      return "ClearCase Client configuration problem"; //$NON-NLS-1$
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      //validate settings
      try {
        Util.execAndWait(Constants.CLEARTOOL_CHECK_AVAILABLE_COMMAND);
        debug(String.format(VALIDATION_PASSED, getClass().getSimpleName()));
        return true;
      } catch (Exception e) {
        debug(e);
        final String message = trim(e.getMessage());
        if (message.contains(UNKNOWN_HOST_PATTERN)) {
          validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, String.format(Messages.getString("ClearCaseValidation.cleartool_cannot_connect_to_host_error_message"), getHost(message)))); //$NON-NLS-1$
          debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, message));

        } else if (message.contains(CANNOT_CONTACT_LICENSE_SERVER_PATTERN)) {
          validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, String.format(Messages.getString("ClearCaseValidation.cleartool_cannot_connect_to_albd_server_error_message"), getHost(message)))); //$NON-NLS-1$
          debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, message));

        } else {
          validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, message)); //$NON-NLS-1$
          debug(String.format("validation: \"%s\" is not matched, failed", message)); //$NON-NLS-1$
        }
      }
      return false;
    }

    private String getHost(String message) {
      int beginIndex = message.indexOf("'"); //$NON-NLS-1$
      if (beginIndex != -1) {
        int nextIndex = message.indexOf("'", beginIndex + 1);
        if (nextIndex != -1) {
          return message.substring(beginIndex + 1, nextIndex); //$NON-NLS-1$
        }
      }
      return message;
    }
  }

  /**
   * Checks Global Labeling properties
   */
  static class ClearcaseGlobalLabelingValidator implements IValidation {

    public String getDescription() {
      return "Check Labeling properties"; //$NON-NLS-1$
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      return checkGlobalLabelsVOBProperty(properties, validationResultBuffer);
    }

    private boolean checkGlobalLabelsVOBProperty(final Map<String, String> properties, final Collection<InvalidProperty> result) {
      final boolean useGlobalLabel = Boolean.parseBoolean(properties.get(Constants.USE_GLOBAL_LABEL));
      final String globalLabelsVOB = properties.get(Constants.GLOBAL_LABELS_VOB);
      if (useGlobalLabel) {
        if (globalLabelsVOB == null || trim(globalLabelsVOB).length() == 0) { //$NON-NLS-1$
          result.add(new InvalidProperty(Constants.GLOBAL_LABELS_VOB, Messages.getString("ClearCaseValidation.global_label_vob_lost_error_message"))); //$NON-NLS-1$
          debug(String.format(DOUBLLE_PARAM_VALIDATION_FAILED, useGlobalLabel, globalLabelsVOB));
          return false;
        }
      }
      debug(String.format(DOUBLLE_PARAM_VALIDATION_PASSED, useGlobalLabel, globalLabelsVOB));
      return true;
    }

  }

  /**
   * Checks the CC View alive
   */
  static class ClearcaseViewValidator implements IValidation {

    public String getDescription() {
      return "Check the ClearCase view is alive"; //$NON-NLS-1$
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      final String ccViewPathRootPath = properties.get(Constants.CC_VIEW_PATH);
      assert ccViewPathRootPath != null;
      if (ReferencesResolverUtil.containsReference(ccViewPathRootPath)) return true;

      try {
        if (checkClearCaseView(Constants.CC_VIEW_PATH, ccViewPathRootPath, validationResultBuffer)) {
          debug(String.format(SINGLE_PARAM_VALIDATION_PASSED, ccViewPathRootPath));
          return true;
        } else {
          debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, ccViewPathRootPath));
          return false;
        }
      } catch (final IOException e) {
        validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, e.getMessage()));
        debug(String.format(DOUBLLE_PARAM_VALIDATION_FAILED, ccViewPathRootPath, e.getMessage()));
        return false;
      }
    }

    private boolean checkClearCaseView(String propertyName, String ccViewPath, Collection<InvalidProperty> result) throws IOException {
      if (!ClearCaseConnection.isClearCaseView(ccViewPath)) {
        result.add(new InvalidProperty(propertyName, String.format(Messages.getString("ClearCaseValidation.clearcase_view_root_path_does_not_point_to_alive_clearcase_view"), ccViewPath))); //$NON-NLS-1$
        return false;
      }
      return true;
    }

    public Collection<InvalidProperty> process(Map<String, String> properties) {
      return Collections.emptyList();
    }

  }

  /**
   * Checks the CC View's root folder set and valid
   */
  static class ClearcaseViewRootPathValidator extends AbstractVcsPropertiesProcessor/*just for checkDirectoryProperty only*/implements IValidation {

    public String getDescription() {
      return "Check ClearCase view path"; //$NON-NLS-1$
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      final String ccViewRootPath = trim(properties.get(Constants.CC_VIEW_PATH));
      //check path exists
      if (isEmpty(ccViewRootPath)) {
        validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, Messages.getString("ClearCaseValidation.clearcase_view_root_path_missed"))); //$NON-NLS-1$
        debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, ccViewRootPath));
        return false;
      }

      assert ccViewRootPath != null;
      if (ReferencesResolverUtil.containsReference(ccViewRootPath)) return true;

      //check paths is well formed
      try {
        CCPathElement.normalizePath(ccViewRootPath);
      } catch (VcsException e) {
        if (!mayContainReference(ccViewRootPath)) {
          validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, e.getMessage()));
          debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, ccViewRootPath));
          return false;
        }
      }
      //check path is directory?
      final int countBefore = validationResultBuffer.size();
      checkDirectoryProperty(Constants.CC_VIEW_PATH, ccViewRootPath, validationResultBuffer);
      if (countBefore != validationResultBuffer.size()) {
        debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, ccViewRootPath));
        return false;
      }
      debug(String.format(SINGLE_PARAM_VALIDATION_PASSED, ccViewRootPath));
      return true;
    }

    public Collection<InvalidProperty> process(Map<String, String> properties) {
      return Collections.emptyList();
    }

  }

  /**
   * Checks the Relative folder of CC View set and valid
   */
  static class ClearcaseViewRelativePathValidator extends AbstractVcsPropertiesProcessor/*just for checkDirectoryProperty only*/implements IValidation {

    public String getDescription() {
      return "Check ClearCase relative view path"; //$NON-NLS-1$
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      //check path exists
      final String ccViewRelativePath = trim(properties.get(Constants.RELATIVE_PATH));
      if (isEmpty(ccViewRelativePath)) {
        validationResultBuffer.add(new InvalidProperty(Constants.RELATIVE_PATH, Messages.getString("ClearCaseValidation.clearcase_view_relative_path_missed"))); //$NON-NLS-1$
        debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, ccViewRelativePath));
        return false;
      }

      assert ccViewRelativePath != null;
      if (ReferencesResolverUtil.containsReference(ccViewRelativePath)) return true;

      //check paths is well formed
      try {
        final String normalizedPath = CCPathElement.normalizePath(ccViewRelativePath);
        if (isEmpty(normalizedPath)) {
          validationResultBuffer.add(new InvalidProperty(Constants.RELATIVE_PATH, Messages.getString("ClearCaseValidation.clearcase_view_relative_path_does_not_point_to_inner_folder"))); //$NON-NLS-1$
        }
      } catch (VcsException e) {
        if (!mayContainReference(ccViewRelativePath)) {
          validationResultBuffer.add(new InvalidProperty(Constants.RELATIVE_PATH, e.getMessage()));
          debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, ccViewRelativePath));
          return false;
        }
      }
      //check path is directory?
      final int countBefore = validationResultBuffer.size();
      String viewPath = trim(properties.get(Constants.CC_VIEW_PATH));
      if (viewPath == null || ReferencesResolverUtil.containsReference(viewPath)) return true;

      final String pathWithinTheView = new File(viewPath, ccViewRelativePath).getAbsolutePath();
      checkDirectoryProperty(Constants.RELATIVE_PATH, pathWithinTheView, validationResultBuffer);
      if (countBefore != validationResultBuffer.size()) {
        debug(String.format(SINGLE_PARAM_VALIDATION_FAILED, ccViewRelativePath));
        return false;
      }
      debug(String.format(SINGLE_PARAM_VALIDATION_PASSED, ccViewRelativePath));
      return true;
    }

    public Collection<InvalidProperty> process(Map<String, String> properties) {
      return Collections.emptyList();
    }

  }

  private static void debug(String message) {
    LOG.debug(message);
  }

  private static void debug(Throwable t) {
    LOG.debug(t.getMessage(), t);
  }

  private static String trim(String val) {
    if (val != null) {
      return val.trim();
    }
    return null;
  }

}
