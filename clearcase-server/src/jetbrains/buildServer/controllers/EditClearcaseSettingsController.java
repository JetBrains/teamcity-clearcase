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

package jetbrains.buildServer.controllers;

import jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport;
import jetbrains.buildServer.buildTriggers.vcs.clearcase.ViewPath;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.web.openapi.ControllerAction;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.vcs.VcsException;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class EditClearcaseSettingsController extends BaseAjaxActionController {
  
  /*
    <!-- c:if test="${showClearCaseNotFound}">
  <tr class="noBorder" id ="psexecPathNoteContainer" style="display:none;">
    <td colspan="2">
      <div class="attentionComment">
        Could not find Sysinternals <a showdiscardchangesmessage="false" target="_blank" href="http://technet.microsoft.com/en-us/sysinternals/bb897553.aspx">psexec.exe</a> tool on the server.<br/>
        Please ensure it is available in the system PATH or specify path to psexec.exe in teamcity.psexec.path server internal property
        <bs:help file="Configuring+TeamCity+Server+Startup+Properties#ConfiguringTeamCityServerStartupProperties-internal.properties"/>.<br/>
      </div>
    </td>
  </tr>
</c:if-->


   */
  
  public EditClearcaseSettingsController(@NotNull final WebControllerManager controllerManager) {
    super(controllerManager);
    
    controllerManager.registerController("/admin/convertOldCCSettings.html", this);
    
    registerAction(new ControllerAction() {
      
      public boolean canProcess(HttpServletRequest request) {
        final String oldViewPath = request.getParameter("view-path-value");
        return oldViewPath != null && oldViewPath.trim().length() != 0;
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
  }

}
