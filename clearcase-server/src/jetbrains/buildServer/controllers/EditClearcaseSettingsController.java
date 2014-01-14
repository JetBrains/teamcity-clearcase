/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

package jetbrains.buildServer.controllers;

import java.util.SortedSet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ViewPath;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.web.openapi.ControllerAction;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EditClearcaseSettingsController extends BaseAjaxActionController {
  
  public EditClearcaseSettingsController(@NotNull final WebControllerManager controllerManager) {
    super(controllerManager);
    
    controllerManager.registerController("/admin/clearCaseSettings.html", this);
    
    registerAction(new ControllerAction() {
      public boolean canProcess(@NotNull final HttpServletRequest request) {
        return "convertOldSettings".equals(request.getParameter("action")) && !StringUtil.isEmptyOrSpaces(request.getParameter("view-path-value"));
      }

      public void process(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @Nullable Element ajaxResponse) {
        if (ajaxResponse == null) {
          Loggers.SERVER.debug("Error: ajaxResponse is null");
          return;
        }

        try {
          final ViewPath viewPath = ClearCaseSupport.getViewPath(request.getParameter("view-path-value"));

          final Element ccViewPath = new Element("cc-view-path");
          ccViewPath.addContent(viewPath.getClearCaseViewPath());

          final Element relPath = new Element("rel-path");
          relPath.addContent(viewPath.getRelativePathWithinTheView());

          ajaxResponse.addContent(ccViewPath);
          ajaxResponse.addContent(relPath);
        } catch (VcsException e) {
          final Element error = new Element("error");
          error.addContent(e.getLocalizedMessage());
          ajaxResponse.addContent(error);
        }
      }
    });

    registerAction(new ControllerAction() {
      public boolean canProcess(@NotNull final HttpServletRequest request) {
        return "detectBranches".equals(request.getParameter("action"));
      }

      public void process(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @Nullable Element ajaxResponse) {
        if (ajaxResponse == null) {
          Loggers.SERVER.debug("Error: ajaxResponse is null");
          return;
        }

        try {
          final String ccViewPath = request.getParameter("cc-view-path");
          final String relPath = request.getParameter("rel-path");
          final String viewPathValue = request.getParameter("view-path-value");

          final ViewPath viewPath;
          if (!StringUtil.isEmptyOrSpaces(ccViewPath) && !StringUtil.isEmptyOrSpaces(relPath)) {
            viewPath = new ViewPath(ccViewPath, relPath);
          }
          else if (!StringUtil.isEmptyOrSpaces(viewPathValue)) {
            viewPath = ClearCaseSupport.getViewPath(viewPathValue);
          }
          else {
            throw new VcsException("view path is not specified");
          }

          final Element result = new Element("result");
          result.addContent(createContent(ClearCaseSupport.detectBranches(viewPath)));
          ajaxResponse.addContent(result);
        }
        catch (final Exception e) {
          final Element error = new Element("error");
          error.addContent(e.getLocalizedMessage());
          ajaxResponse.addContent(error);
        }
      }

      @NotNull
      private String createContent(@NotNull final SortedSet<String> branches) {
        if (branches.isEmpty()) {
          return "No branches detected";
        }
        return "Following branches were detected: " + StringUtil.join(branches, ", ");
      }
    });
  }
}
