<%--
  ~ Copyright 2000-2011 JetBrains s.r.o.
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
<%@ page import="jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport" %>
<%@ page import="jetbrains.buildServer.vcs.clearcase.Constants" %>

<jsp:useBean id="propertiesBean" scope="request" type="jetbrains.buildServer.controllers.BasePropertiesBean"/>
<jsp:useBean id="clearcaseSupport" scope="request" class="jetbrains.buildServer.buildTriggers.vcs.clearcase.ClearCaseSupport" />

<script type="text/javascript" src="/plugins/clearcase/js/clearcaseSettings.js"></script>

<c:set var="showClearCaseNotFound" value="${clearcaseSupport.clearCaseClientNotFound}"/>
<c:set var="clearCaseNotFoundText" value="<%=Constants.CLIENT_NOT_FOUND_MESSAGE%>"/>
<c:set var="showOldSettings" value="${propertiesBean.properties['view-path'] != null && not empty propertiesBean.properties['view-path']}"/>

<bs:linkCSS>
    /css/main.css
    /css/forms.css
    /css/project.css
    /css/admin/adminMain.css
</bs:linkCSS>

<c:if test="${showOldSettings}">
    <style type="text/css">
        #oldSettingsMessageInternal {
            margin: auto;
            padding: 2px 20px;
            font-size: 90%;
            text-align: center;
            font-weight: bold;
            background-color: #ffffcc;
        }
    </style>
    <div id="oldSettingsMessage"><div id="oldSettingsMessageInternal">Settings are obsolete. Please click "Convert to new settings..." button or type new settings manually. You can also click "Cancel" link to continue to use obsolete settings.</div><br/></div>
</c:if>

<table class="runnerFormTable">
<l:settingsGroup title="ClearCase Settings">

<c:if test="${showClearCaseNotFound}">
  <tr>
    <td colspan="2">
      <div class="attentionComment">
      <font color='red'>${clearCaseNotFoundText}</font><br/>
      See online documentation for more detail<bs:help file="ClearCase#ClearCase"/><br/>
      </div>
    </td>
  </tr>
</c:if>

<c:if test="${showOldSettings}">
    <tr id="oldSettingsRow">
        <th><label for="view-path" style="text-decoration: line-through">View path:</label>
        </th>
        <td>
            <input style="float: right;" type="button" value="Convert to new settings..." onclick="BS.ClearCaseSettings.convertSettings();"/>
            <forms:saving id="convertSettingsProgressIcon"/>
            <props:textProperty name="view-path" className="longField" />
            <span class="error" id="error_view-path"></span>
            <div class="smallNote" style="margin-left: 0;">
              Obsolete setting. Please see the message above.
            </div>
        </td>
    </tr>
</c:if>
<tr>
  <th><label for="cc-view-path">ClearCase view path: <l:star/></label>
  </th>
  <td><props:textProperty name="cc-view-path" className="longField" />
    <span class="error" id="error_cc-view-path"></span></td>
</tr>
<tr>
  <th><label for="rel-path">Relative path within the view: <bs:help file="ClearCase" anchor="relPathOptionDescription"/> <l:star/></label>
  </th>
  <td><props:textProperty name="rel-path" className="longField" />
    <span class="error" id="error_rel-path"></span></td>
</tr>
<tr>
  <th class="noBorder"><label for="TYPE">Use ClearCase:</label></th>
  <td class="noBorder"><props:selectProperty name="TYPE">
        <props:option value="UCM">UCM</props:option>
        <props:option value="BASE">BASE</props:option>
      </props:selectProperty></td>
</tr>
</l:settingsGroup>
<l:settingsGroup title="Labling settings">
<tr>
  <th><label for="use-global-label">Global labeling:</label></th>
  <td>
    <props:checkboxProperty name="use-global-label" onclick="$('global-labels-vob').disabled = this.checked ? '' : 'disabled'; BS.VisibilityHandlers.updateVisibility($('mainContent'));" />
    <label for="use-global-label">Use global labels</label>
  </td>
</tr>
<tr>
  <th><label for="global-labels-vob">Global labels VOB:</label></th>
  <td>
    <props:textProperty name="global-labels-vob" className="longField" disabled="${propertiesBean.properties['use-global-label'] != 'true'}"/>
    <span class="error" id="error_global-labels-vob"></span>
    <div class="smallNote" style="margin-left: 0;">
      Pathname of the VOB tag (whether or not the VOB is mounted) or of any file system object within the VOB (if the VOB is mounted)
    </div>
  </td>
</tr>
</l:settingsGroup>
</table>
