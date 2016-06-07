<%--
  ~ Copyright 2000-2014 JetBrains s.r.o.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~ http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  --%>

<%@include file="/include.jsp"%>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="bs" tagdir="/WEB-INF/tags" %>
<%@ page import="jetbrains.buildServer.vcs.clearcase.Constants" %>

<jsp:useBean id="propertiesBean" scope="request" type="jetbrains.buildServer.controllers.BasePropertiesBean"/>
<jsp:useBean id="clearcaseSupport" scope="request" class="jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport" />

<script type="text/javascript">
  BS.ClearCaseSettings = {
    convertSettings: function () {
      BS.Util.show('convertSettingsProgressIcon');

      BS.VcsSettingsForm.clearErrors();
      BS.VcsSettingsForm.disable();

      BS.ajaxRequest(window['base_uri'] + "/admin/clearCaseSettings.html?action=convertOldSettings", {
        parameters: {
          "view-path-value": $("view-path").value
        },

        onComplete:function (transport) {
          BS.VcsSettingsForm.enable();

          BS.Util.hide('convertSettingsProgressIcon');

          var xml = transport.responseXML;

          if (xml == null) {
            alert("Error: server response is null");
            return;
          }

          var firstChild = xml.documentElement.firstChild;
          var firstChildText = firstChild.textContent || firstChild.text;

          if (firstChild.nodeName == 'error') {
            alert("Error: " + firstChildText);
            return;
          }

          $('oldSettingsRow').style.display = "none";
          $('oldSettingsMessage').style.display = "none";

          var secondChild = firstChild.nextSibling;

          $('view-path').value = "";
          $('cc-view-path').value = firstChildText;
          $('rel-path').value = secondChild.textContent || secondChild.text;
        }
      });
    },

    detectBranches: function () {
      $("detectBranchesButton").disabled = true;
      BS.Util.show('detectBranchesProgressIcon');

      BS.ajaxRequest(window['base_uri'] + "/admin/clearCaseSettings.html?action=detectBranches", {
        parameters: {
          "cc-view-path": $("cc-view-path").value,
          "rel-path": $("rel-path").value,
          "view-path-value": $("view-path") ? $("view-path").value : ""
        },

        onComplete:function (transport) {
          BS.Util.hide('detectBranchesProgressIcon');
          $("detectBranchesButton").disabled = false;

          var xml = transport.responseXML;
          if (xml == null) {
            $("detectedBranchesErrorSpan").innerHTML = "Error: server is not available";
            BS.Util.hide("detectedBranchesSpan");
            BS.Util.show("detectedBranchesErrorSpan");
            return;
          }

          var firstChild = xml.documentElement.firstChild;
          var text = firstChild.textContent || firstChild.text;

          if (firstChild.nodeName == 'error') {
            $("detectedBranchesErrorSpan").innerHTML = "Error: " + text;
            BS.Util.hide("detectedBranchesSpan");
            BS.Util.show("detectedBranchesErrorSpan");
          }
          else {
            $("detectedBranchesSpan").innerHTML = text;
            BS.Util.hide("detectedBranchesErrorSpan");
            BS.Util.show("detectedBranchesSpan");
          }
        }
      });
    },

    branchProviderChanged: function (auto) {
      $('branches').disabled = auto;
      BS.VisibilityHandlers.updateVisibility('branches');
    }
  };
</script>

<c:set var="showClearCaseNotFound" value="${clearcaseSupport.clearCaseClientNotFound}"/>
<c:set var="clearCaseNotFoundText" value="<%=Constants.CLIENT_NOT_FOUND_MESSAGE%>"/>
<c:set var="showOldSettings" value="${propertiesBean.properties['view-path'] != null && not empty propertiesBean.properties['view-path']}"/>

<bs:linkCSS dynamic="${true}">
  /css/FontAwesome/css/font-awesome.min.css
  /css/main.css
  /css/forms.css
  /css/project.css
  /css/admin/adminMain.css
</bs:linkCSS>

<c:if test="${showOldSettings}">
  <div id="oldSettingsMessage">
    <div class="successMessage">Settings are obsolete. Please click "Convert to new settings..." button or type new settings manually. You
      can also click "Cancel" link to continue to use obsolete settings.
    </div>
  </div>
</c:if>

<table class="runnerFormTable">
  <l:settingsGroup title="ClearCase Settings">
    <c:if test="${showClearCaseNotFound}">
      <tr>
        <td colspan="2">
          <forms:attentionComment>
          <font color='red'>${clearCaseNotFoundText}</font><br/>
          See online documentation for more details<bs:help file="ClearCase#ClearCase"/><br/>
          </forms:attentionComment>
        </td>
      </tr>
    </c:if>

    <c:if test="${showOldSettings}">
      <tr id="oldSettingsRow">
        <th><label for="view-path" style="text-decoration: line-through">View path:</label>
        </th>
        <td>
          <input style="float: right;" type="button" class="btn btn_mini" value="Convert to new settings..." onclick="BS.ClearCaseSettings.convertSettings();"/>
          <forms:saving id="convertSettingsProgressIcon"/>
          <props:textProperty name="view-path" className="longField" />
          <span class="error" id="error_view-path"></span>
          <bs:smallNote>Obsolete setting. Please see the message above.</bs:smallNote>
        </td>
      </tr>
    </c:if>

    <tr>
      <th><label for="cc-view-path">ClearCase view path: <l:star/></label>
      </th>
      <td><props:textProperty name="cc-view-path" className="longField"/>
        <span class="error" id="error_cc-view-path"></span></td>
    </tr>
    <tr>
      <th><label for="rel-path">Relative path within the view: <l:star/> <bs:help file="ClearCase" anchor="relPathOptionDescription"/></label>
      </th>
      <td><props:textProperty name="rel-path" className="longField"/>
        <span class="error" id="error_rel-path"></span></td>
    </tr>
    <tr class="advancedSetting">
      <th><label>Branches:</label> <bs:help file="ClearCase" anchor="branchesOptionDescription"/></th>
      <td>
        <props:radioButtonProperty name="branch-provider"
                                   onclick="BS.ClearCaseSettings.branchProviderChanged(true);"
                                   value="auto"
                                   id="branchAutoProvider"
                                   checked='${empty propertiesBean.properties["branch-provider"] or (propertiesBean.properties["branch-provider"] eq "auto")}'/>
        <label for="branchAutoProvider">detect automatically</label>
        <input type="button" class="btn btn_mini" value="Detect now" id="detectBranchesButton" onclick="BS.ClearCaseSettings.detectBranches();"/>
        <forms:saving id="detectBranchesProgressIcon" className="progressRingInline"/>
        <br/>
        <span id="detectedBranchesSpan" style="display: none;"></span><span class="error" id="detectedBranchesErrorSpan" style="display: none;"></span>
      </td>
    </tr>
    <tr class="advancedSetting">
      <th>&nbsp;</th>
      <td>
        <props:radioButtonProperty name="branch-provider"
                                   onclick="BS.ClearCaseSettings.branchProviderChanged(false);"
                                   value="custom"
                                   id="branchCustomProvider"/>
        <label for="branchCustomProvider">custom:</label>
        <div>
          <c:set var="disabled"><c:out value="${propertiesBean.properties['branch-provider'] ne 'custom'}"/></c:set>
          <props:textProperty name="branches" className="mediumField" disabled="${disabled}"/>
          <span class="error" id="error_branches"></span>
        </div>
        <bs:smallNote>You can leave this field blank or specify several branches separated by spaces, commas or semicolons.</bs:smallNote>
      </td>
    </tr>
    <tr class="advancedSetting">
      <th class="noBorder"><label for="TYPE">Use ClearCase:</label></th>
      <td class="noBorder">
        <props:selectProperty name="TYPE" enableFilter="true" className="mediumField">
          <props:option value="UCM">UCM</props:option>
          <props:option value="BASE">BASE</props:option>
        </props:selectProperty>
      </td>
    </tr>
  </l:settingsGroup>

  <l:settingsGroup title="Labeling settings" className="advancedSetting">
    <tr class="advancedSetting">
      <th><label for="use-global-label">Global labeling:</label></th>
      <td>
        <props:checkboxProperty name="use-global-label"
                                onclick="$('global-labels-vob').disabled = this.checked ? '' : 'disabled'; BS.VisibilityHandlers.updateVisibility('mainContent');"/>
        <label for="use-global-label">Use global labels</label>
      </td>
    </tr>
    <tr class="advancedSetting">
      <th><label for="global-labels-vob">Global labels VOB:</label></th>
      <td>
        <props:textProperty name="global-labels-vob" className="longField" disabled="${propertiesBean.properties['use-global-label'] != 'true'}"/>
        <span class="error" id="error_global-labels-vob"></span>

        <bs:smallNote>Pathname of the VOB tag (whether or not the VOB is mounted) or of any file system object within the VOB (if the VOB is mounted)</bs:smallNote>
      </td>
    </tr>
  </l:settingsGroup>
</table>
