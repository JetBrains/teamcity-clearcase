/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
import java.util.List;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.VcsRoot;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public class RuleBasedSourceProvider extends CheckoutDirectoryBasedSourceProvider {

  private static final String VALIDATION_MESSAGES_PREFIX = "Cannot perform ClearCase agent-side checkout:";
  private static final String VALIDATION_WRONG_CHECKOUT_RULES_MESSAGE = String.format("%s Only single checkout rule in the form of \".=>...\" is supported for ClearCase agent-side checkout.", VALIDATION_MESSAGES_PREFIX);

  static final Logger LOG = Logger.getLogger(RuleBasedSourceProvider.class);

  public RuleBasedSourceProvider() {
  }

  public void validate(final @NotNull File checkoutRoot/*final @NotNull AgentRunningBuild build*/, final @NotNull VcsRoot vcsRoot, final @NotNull CheckoutRules rules) throws VcsValidationException {
    LOG.debug(String.format("Validating Checkout Rules '%s'", rules.toString().trim()));
    final List<IncludeRule> includeRules = rules.getIncludeRules();
    if (includeRules.size() == 1) {
      final IncludeRule mainRule = includeRules.get(0);
      if (mainRule.getFrom().trim().length() == 0 || ".".equals(mainRule.getFrom())) {
        LOG.debug(String.format("Checkout rule accepted: '%s'. Checking Rule path...", mainRule));
        final File ccViewRoot = getRelativeClearCaseVievRootDirectory(vcsRoot, checkoutRoot/*build.getCheckoutDirectory()*/, mainRule);
        LOG.debug(String.format("Checkout Rule validated: got '%s' ClearCase View's root", ccViewRoot.getAbsolutePath()));
        return;

      } else {
        LOG.debug(String.format("Checkout Reles Validation failed: From=%s", mainRule.getFrom()));
      }
    } else {
      LOG.debug(String.format("Checkout Reles Validation failed: Count=%d", includeRules.size()));
    }
    throw new VcsValidationException(VALIDATION_WRONG_CHECKOUT_RULES_MESSAGE);
  }

  @Override
  protected File getCCRootDirectory(final @NotNull AgentRunningBuild build, final @NotNull VcsRoot vcsRoot, final @NotNull File checkoutRoot, final @NotNull CheckoutRules rules) throws VcsValidationException {
    final File relativeDirectory = getRelativeClearCaseVievRootDirectory(vcsRoot, checkoutRoot, rules.getIncludeRules().get(0));
    return relativeDirectory;
  }

  protected File getRelativeClearCaseVievRootPath(final @NotNull File checkoutRoot, final @NotNull File ruleToPath, final @NotNull File relativePathWitninAView) throws VcsValidationException {
    final String relativePathWitninAViewPath = relativePathWitninAView.getPath();
    final String checkoutRuleRightPath = ruleToPath.getPath();
    final File relativePath;
    try {
      final String fullCheckoutFolderPath = getFullCheckoutPath(checkoutRoot, checkoutRuleRightPath);
      LOG.debug(String.format("fullCheckoutFolderPath=%s", fullCheckoutFolderPath));
      if (!fullCheckoutFolderPath.endsWith(relativePathWitninAViewPath)) {
        final String errorMessage = String.format("%s Resulting checkout path (%s) should end with mandatory ClearCase-enforced path (%s) while using agent-side checkout. Please adjust checkout directory and/or right part of checkout rule correspondingly.", VALIDATION_MESSAGES_PREFIX,
            fullCheckoutFolderPath, relativePathWitninAViewPath);
        throw new VcsValidationException(String.format(errorMessage, fullCheckoutFolderPath, relativePathWitninAViewPath));
      }
      //make path relative to checkout root 
      String pathSegment = fullCheckoutFolderPath.substring(0/*checkoutRoot.getPath().length()*/, fullCheckoutFolderPath.length() - relativePathWitninAViewPath.length());
      //drop separator if exists 
      if(pathSegment.startsWith("\\") || pathSegment.startsWith("/")){
        pathSegment = pathSegment.substring(1, pathSegment.length());
      }
      LOG.debug(String.format("pathSegment=%s", pathSegment));
      relativePath = new File(pathSegment);
      LOG.debug(String.format("Relative path within checkout directory: '%s'", relativePath));
      return relativePath;

    } catch (IOException e) {
      throw new VcsValidationException(e);
    }

  }

  public static String getFullCheckoutPath(final File checkoutRoot, final String checkoutRuleRightPath) throws IOException {
    LOG.debug(String.format("checkoutRoot=%s", checkoutRoot));
    LOG.debug(String.format("checkoutRuleRightPath=%s", checkoutRuleRightPath));    
    return new File(FileUtil.normalizeRelativePath(checkoutRoot.getPath()), FileUtil.normalizeRelativePath(checkoutRuleRightPath)).getPath();
  }

  protected File getRelativeClearCaseVievRootDirectory(/*final @NotNull AgentRunningBuild build, */final @NotNull VcsRoot vcsRoot, final @NotNull File checkoutRoot, final @NotNull IncludeRule includeRule) throws VcsValidationException {
    //VCS & Runner settings:
    //Checkout directory: dev/views
    //ClearCase view path: c:\eprom\dev\views\isl_prd_mdl
    //Relative path within the view:  isl\product_model
    //Checkout Rule: +:.=>isl_prd_mdl/isl/product_model

    //Command executable: isl_prd_mdl/isl/product_model/gp0.cmd

    //check: "Checkout directory" + "Checkout Rule" .endWith "Relative path within the view" 

    final File mainCheckoutRulePath = getRulePath(includeRule);//+:.=>isl_prd_mdl/isl/product_model
    LOG.debug(String.format("Main Checkout Rule path: '%s'", mainCheckoutRulePath.getPath())); //$NON-NLS-1$
    final File relativePathWitninAView = getRelativePathWithinAView(vcsRoot);//isl/product_model
    LOG.debug(String.format("Relative Path within a View: '%s'", relativePathWitninAView)); //$NON-NLS-1$    
    final File clearCaseViewPathRelativeToCheckoutRoot = getRelativeClearCaseVievRootPath(/*build, */checkoutRoot, mainCheckoutRulePath, relativePathWitninAView);//must be 'isl_prd_mdl'
    LOG.debug(String.format("Relative path for ClearCase view: '%s'", clearCaseViewPathRelativeToCheckoutRoot)); //$NON-NLS-1$
    LOG.debug(String.format("Root directory for view creation: '%s'", clearCaseViewPathRelativeToCheckoutRoot /*ccViewRoot*/.getAbsolutePath())); //$NON-NLS-1$    
    return clearCaseViewPathRelativeToCheckoutRoot /*ccViewRoot*/;
  }

  static File getRulePath(final @NotNull IncludeRule rule) {
    return new File(rule.getTo());
  }

}
