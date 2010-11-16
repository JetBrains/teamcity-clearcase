/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.clearcase.Constants;
import jetbrains.buildServer.vcs.clearcase.Util;

import org.apache.log4j.Logger;

public class ClearCaseValidation {

  private static final Logger LOG = Logger.getLogger(ClearCaseValidation.class);

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
      boolean canRun = Util.canRun("cleartool");
      if (!canRun) {
        final String reason = String.format("\"cleartool\" is not in PATH");
        validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, reason));
        debug(String.format("%s.validate(): failed", getClass().getSimpleName()));        
      } else {
        debug(String.format("%s.validate(): passed", getClass().getSimpleName()));    
      }
      return canRun;
    }

    public String getDescription() {
      return "Check the \"cleartool\" runnable";
    }
  }

  /**
   * Checks ClearCase is configured properly
   */
  static class ClearcaseConfigurationValidator implements IValidation {

    static final String CANNOT_CONTACT_LICENSE_SERVER_PATTERN = "Unable to contact albd_server on host";

    public String getDescription() {
      return "ClearCase Client configuration problem";
    }

//    static final Pattern CLIENT_IS_NOT_CONFIGURED = Pattern.compile("(.*)cleartool: Error: Unable to contact albd_server on host '(.*)'(.*)cleartool: Error: (.*)");
    
    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      try {
        Util.execAndWait("cleartool hostinfo");
        debug(String.format("%s.validate(): passed", getClass().getSimpleName()));        
        return true;
      } catch (Exception e) {
        debug(e);
        final String message = trim(e.getMessage());
        if(message.contains(CANNOT_CONTACT_LICENSE_SERVER_PATTERN)){
          int beginIndex = message.indexOf("'");
          final String host = message.substring(beginIndex + 1, message.indexOf("'", beginIndex + 1));
          validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, String.format("ClearCase Client is not configured properly:\n\tError: Unable to contact albd_server on host '%s'", host)));
          debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), message));          
        } else {
          debug(String.format("%s.validate(): \"%s\" is not matched, failed", getClass().getSimpleName(), message));
        }
      }
      return false;
    }
  }
  
  /**
   * Checks Global Labeling properties
   */
  static class ClearcaseGlobalLabelingValidator implements IValidation {

    public String getDescription() {
      return "Check Labeling properties";
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      return checkGlobalLabelsVOBProperty(properties, validationResultBuffer);
    }

    private boolean checkGlobalLabelsVOBProperty(final Map<String, String> properties, final Collection<InvalidProperty> result) {
      final boolean useGlobalLabel = "true".equals(properties.get(Constants.USE_GLOBAL_LABEL));
      final String globalLabelsVOB = properties.get(Constants.GLOBAL_LABELS_VOB);      
      if (useGlobalLabel) {
        if (globalLabelsVOB == null || "".equals(trim(globalLabelsVOB))) {
          result.add(new InvalidProperty(Constants.GLOBAL_LABELS_VOB, "Global labels VOB must be specified"));
          debug(String.format("%s.validate(%s, \"%s\"): failed", getClass().getSimpleName(), useGlobalLabel, globalLabelsVOB));          
          return false;
        }
      }
      debug(String.format("%s.validate(%s, \"%s\"): passed", getClass().getSimpleName(), useGlobalLabel, globalLabelsVOB));
      return true;
    }

  }

  /**
   * Checks the CC View alive
   */
  static class ClearcaseViewValidator implements IValidation {

    public String getDescription() {
      return "Check the ClearCase view is alive";
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      final String ccViewPathRootPath = properties.get(Constants.CC_VIEW_PATH);      
      try {
        if(checkClearCaseView(Constants.CC_VIEW_PATH, ccViewPathRootPath, validationResultBuffer)){
          debug(String.format("%s.validate(\"%s\"): passed", getClass().getSimpleName(), ccViewPathRootPath));
          return true;
        } else {
          debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), ccViewPathRootPath));
          return false;          
        }
      } catch (final IOException e) {
        validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, e.getMessage()));
        debug(String.format("%s.validate(\"%s\"): %s", getClass().getSimpleName(), ccViewPathRootPath, e.getMessage()));        
        return false;
      }
    }

    private boolean checkClearCaseView(String propertyName, String ccViewPath, Collection<InvalidProperty> result) throws IOException {
      if (!ClearCaseConnection.isClearCaseView(ccViewPath)) {
        result.add(new InvalidProperty(propertyName, "\"" + ccViewPath + "\" is not a path to ClearCase view\nCheck your \"ClearCase view path\" setting"));
        return false;
      }
      return true;
    }

    public Collection<InvalidProperty> process(Map<String, String> properties) {
      return Collections.<InvalidProperty> emptyList();
    }

  }
  
  /**
   * Checks the CC View's root folder set and valid
   */
  static class ClearcaseViewRootPathValidator extends AbstractVcsPropertiesProcessor/*just for checkDirectoryProperty only*/implements IValidation {

    public String getDescription() {
      return "Check ClearCase view path";
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      final String ccViewRootPath = trim(properties.get(Constants.CC_VIEW_PATH));
      //check path exists
      if (isEmpty(ccViewRootPath)) {
        validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, "ClearCase view path must be specified"));
        debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), ccViewRootPath));        
        return false;
      }
      //check paths is well formed 
      try {
        CCPathElement.normalizePath(ccViewRootPath);
      } catch (VcsException e) {
        validationResultBuffer.add(new InvalidProperty(Constants.CC_VIEW_PATH, e.getMessage()));
        debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), ccViewRootPath));
        return false;
      }
      //check path is directory? 
      final int countBefore = validationResultBuffer.size();
      checkDirectoryProperty(Constants.CC_VIEW_PATH, ccViewRootPath, validationResultBuffer);
      if (countBefore != validationResultBuffer.size()) {
        debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), ccViewRootPath));        
        return false;
      }
      debug(String.format("%s.validate(\"%s\"): passed", getClass().getSimpleName(), ccViewRootPath));      
      return true;
    }

    public Collection<InvalidProperty> process(Map<String, String> properties) {
      return Collections.<InvalidProperty> emptyList();
    }

  }

  /**
   * Checks the Relative folder of CC View set and valid
   */
  static class ClearcaseViewRelativePathValidator extends AbstractVcsPropertiesProcessor/*just for checkDirectoryProperty only*/implements IValidation {

    public String getDescription() {
      return "Check ClearCase relative view path";
    }

    public boolean validate(Map<String, String> properties, Collection<InvalidProperty> validationResultBuffer) {
      //check path exists
      final String ccViewRelativePath = trim(properties.get(Constants.RELATIVE_PATH));
      if (isEmpty(ccViewRelativePath)) {
        validationResultBuffer.add(new InvalidProperty(Constants.RELATIVE_PATH, "Relative path must be specified"));
        debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), ccViewRelativePath));        
        return false;
      }
      //check paths is well formed 
      try {
        final String normalizedPath = CCPathElement.normalizePath(ccViewRelativePath);
        if (isEmpty(normalizedPath)) {
          validationResultBuffer.add(new InvalidProperty(Constants.RELATIVE_PATH, "Relative path must not be equal to \".\". At least VOB name must be specified."));
        }
      } catch (VcsException e) {
        validationResultBuffer.add(new InvalidProperty(Constants.RELATIVE_PATH, e.getMessage()));
        debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), ccViewRelativePath));        
        return false;
      }
      //check path is directory? 
      final int countBefore = validationResultBuffer.size();
      final String pathWithinTheView = new File(trim(properties.get(Constants.CC_VIEW_PATH)), ccViewRelativePath).getAbsolutePath();
      checkDirectoryProperty(Constants.RELATIVE_PATH, pathWithinTheView, validationResultBuffer);
      if (countBefore != validationResultBuffer.size()) {
        debug(String.format("%s.validate(\"%s\"): failed", getClass().getSimpleName(), ccViewRelativePath));        
        return false;
      }
      debug(String.format("%s.validate(\"%s\"): passed", getClass().getSimpleName(), ccViewRelativePath));      
      return true;
    }

    public Collection<InvalidProperty> process(Map<String, String> properties) {
      return Collections.<InvalidProperty> emptyList();
    }

  }
  
  private static void debug(String message) {
    LOG.debug(message);
//    System.out.println(message);
  }

  private static void debug(Throwable t) {
    LOG.debug(t);
//    t.printStackTrace(System.out);
  }

  private static String trim(String val) {
    if (val != null) {
      return val.trim();
    }
    return null;
  }

}
