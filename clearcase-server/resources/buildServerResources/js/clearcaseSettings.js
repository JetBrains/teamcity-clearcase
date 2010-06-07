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

BS.ClearCaseSettings = {
    convertSettings : function() {
        BS.Util.show($('convertSettingsProgressIcon'));

        BS.VcsSettingsForm.clearErrors();
        BS.VcsSettingsForm.disable();

        BS.ajaxRequest("/admin/convertOldCCSettings.html", {
            parameters: {
                "view-path-value": $("view-path").value
            },

            onComplete: function(transport) {
                BS.VcsSettingsForm.enable();

                BS.Util.hide($('convertSettingsProgressIcon'));

                var xml = transport.responseXML;

                if (xml == null) {
                    alert("Error: server response is null");
                    return;
                }

                var firstChild = xml.documentElement.firstChild;

                if (firstChild.nodeName == 'error') {
                    alert("Error: " + firstChild.textContent);
                    return;
                }

                $('oldSettingsRow').style.display = "none";
                $('oldSettingsMessage').style.display = "none";

                var secondChild = firstChild.nextSibling;

                $('view-path').value = "";
                $('cc-view-path').value = firstChild.textContent;
                $('rel-path').value = secondChild.textContent;
            }
        });
    }
};