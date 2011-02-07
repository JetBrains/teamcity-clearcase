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
import java.util.List;

import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.IncludeRule;
import jetbrains.buildServer.vcs.VcsRoot;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public class RuleBasedSourceProvider extends CheckoutDirectoryBasedSourceProvider {

  static final Logger LOG = Logger.getLogger(RuleBasedSourceProvider.class);

  public RuleBasedSourceProvider() {
  }

  public static boolean accept(final @NotNull AgentRunningBuild build, final @NotNull VcsRoot vcsRoot, final @NotNull CheckoutRules rules) {
    LOG.debug(String.format("Validating Checkout Rules '%s'", rules.toString().trim()));
    if (CheckoutRules.DEFAULT.equals(rules)) {
      LOG.debug(String.format("Default Checkout Rule is not supported: '%s'", rules.toString().trim()));
      return false;
    }
    final List<IncludeRule> includeRules = rules.getIncludeRules();
    if (includeRules.size() == 1) {
      final IncludeRule mainRule = includeRules.get(0);
      if (mainRule.getFrom().trim().length() == 0 || ".".equals(mainRule.getFrom())) {
        LOG.debug(String.format("Checkout rule accepted: '%s'. Checking Rule path...", mainRule));
        try {
          final File ccViewRoot = getClearCaseViewRootDirectory(build, vcsRoot, mainRule);
          LOG.debug(String.format("Checkout Rule validated: got '%s' ClearCase View's root", ccViewRoot.getAbsolutePath()));
          return true;
        } catch (VcsValidationException e) {
          LOG.debug(e.getMessage());
        }
      }
    }
    return false;
  }

  @Override
  protected File getCCRootDirectory(final @NotNull AgentRunningBuild build, final @NotNull VcsRoot vcsRoot, final @NotNull File checkoutRoot, final @NotNull CheckoutRules rules) throws VcsValidationException {
    return getClearCaseViewRootDirectory(build, vcsRoot, rules.getIncludeRules().get(0));
  }

  static File getRelativeClearCaseVievRootPath(final @NotNull AgentRunningBuild build, final @NotNull File checkoutRule, final @NotNull File viewRoot) throws VcsValidationException {
    final String viewRootPath = viewRoot.getPath();
    final String checkoutRulePath = checkoutRule.getPath();
    if (!checkoutRulePath.endsWith(viewRootPath)) {
      throw new VcsValidationException(String.format("\"Relative path within a view\" '%s' doesn't match \"Checkout rule\" '%s'", viewRootPath, checkoutRulePath));
    }
    final File relativePath = new File(checkoutRulePath.substring(0, checkoutRulePath.length() - viewRootPath.length()));
    LOG.debug(String.format("Relative path within checkout directory: '%s'", relativePath));
    return relativePath;
  }

  static File getClearCaseViewRootDirectory(final @NotNull AgentRunningBuild build, final @NotNull VcsRoot vcsRoot, final @NotNull IncludeRule includeRule) throws VcsValidationException {
    final File mainCheckoutRulePath = getRulePath(includeRule);//+:.=>isl_prd_mdl/isl/product_model
    LOG.debug(String.format("Main Checkout Rule path: '%s'", mainCheckoutRulePath.getPath())); //$NON-NLS-1$
    final File viewRootPath = getRelativePathWithinAView(vcsRoot);//isl/product_model
    LOG.debug(String.format("Relative Path within a View: '%s'", viewRootPath)); //$NON-NLS-1$    
    final File relativeCCRootPath = getRelativeClearCaseVievRootPath(build, mainCheckoutRulePath, viewRootPath);//must be 'isl_prd_mdl'
    LOG.debug(String.format("Relative path for ClearCase view: '%s'", relativeCCRootPath)); //$NON-NLS-1$    
    final File ccViewRoot = new File(build.getCheckoutDirectory(), relativeCCRootPath.getPath());
    LOG.debug(String.format("Root directory for view creation: '%s'", ccViewRoot.getAbsolutePath())); //$NON-NLS-1$    
    return ccViewRoot;
  }

  static File getRulePath(final @NotNull IncludeRule rule) {
    return new File(rule.getTo());
  }

}
