import java.util.TimeZone
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import groovy.transform.Canonical
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.component.ComponentAccessor
import com.adaptavist.hapi.jira.properties.EntityProperties

@Canonical
class AutomationLogEntry {
    Integer ruleId;
    String ruleName;
    Long timestamp;
    String component;
    String category;
}

final String PROPERTY_KEY = "com.troshin.jira.automation.tracer";
final String JIRA_BASE_URL = ComponentAccessor.applicationProperties.getJiraBaseUrl()

final Map<String, String> COMPONENT_ICONS = [
        "TRIGGER"  : "aui-iconfont-arrow-right",
        "CONDITION": "aui-iconfont-branch",
        "BRANCH"   : "aui-iconfont-devtools-fork",
        "ACTION"   : "aui-iconfont-addon"
];

final Map<String, List<String>> CATEGORY_CONFIG = [
        "SUCCESS"             : ["aui-lozenge-success", "Success"],
        "FAILURE"             : ["aui-lozenge-removed", "Failure"],
        "SOME_ERRORS"         : ["aui-lozenge-moved", "Some errors"],
        "NO_ACTIONS_PERFORMED": ["", "Skipped"]
];

Issue issue = context.get("issue") as Issue;
if (issue) {
    EntityProperties issueProperties = issue.getEntityPropertiesOverrideSecurity();
    String automationTracerPropertyValue = issueProperties.getJson(PROPERTY_KEY);
    ApplicationUser currentUser = ComponentAccessor.jiraAuthenticationContext.getLoggedInUser()
    String userTimeZone = ComponentAccessor.userPreferencesManager.getExtendedPreferences(currentUser).getString("jira.user.timezone")

    List<AutomationLogEntry> entries = new JsonSlurper()
            .parseText(automationTracerPropertyValue)
            .collect { Map it ->
                new AutomationLogEntry(
                        ruleId: it.ruleId as Integer,
                        ruleName: it.ruleName as String,
                        timestamp: it.timestamp as Long,
                        component: it.component as String,
                        category: it.category as String
                );
            };

    StringBuilder html = new StringBuilder()
    html.append("<table class='aui'>")
    html.append("<thead><tr>")
    html.append("<th></th>")
    html.append("<th>Rule</th>")
    html.append("<th>Status</th>")
    html.append("<th>Time</th>")
    html.append("</tr></thead>")
    html.append("<tbody>")
    String htmlAutomationLogEntries = entries.collect { AutomationLogEntry entry ->
        StringBuilder htmlAutomationLogEntry = new StringBuilder();
        String componentIcon = getComponentIcon(entry.component, COMPONENT_ICONS);
        String categoryLozenge = getCategoryLozenge(entry.category, CATEGORY_CONFIG);
        htmlAutomationLogEntry.append("<tr>")
        htmlAutomationLogEntry.append("<td style='width: 20px'>${componentIcon}</td>")
        htmlAutomationLogEntry.append("<td><a href='${JIRA_BASE_URL}/secure/AutomationGlobalAdminAction!default.jspa#/rule/${entry.ruleId}/audit-log'>${entry.ruleName}</a></td>")
        htmlAutomationLogEntry.append("<td style='white-space: nowrap'>${categoryLozenge}</td>")
        htmlAutomationLogEntry.append("<td>${formatTimestamp(entry.timestamp, userTimeZone)}</td>")
        htmlAutomationLogEntry.append("</tr>")
        return htmlAutomationLogEntry.toString();
    }.join("\n");
    html.append(htmlAutomationLogEntries)
    html.append("</tbody>")
    html.append("</table>")
    writer.write("${html}");
}

String getComponentIcon(String component, Map<String, String> componentIcons) {
    String iconClass = componentIcons.getOrDefault(component, "aui-iconfont-question-circle");
    return "<span class=\"aui-icon aui-icon-small ${iconClass}\" title=\"${component}\"></span>";
}

String getCategoryLozenge(String category, Map<String, List<String>> categoryConfig) {
    List<String> config = categoryConfig.get(category);
    if (!config) {
        return "<span class=\"aui-lozenge aui-lozenge-subtle\">${category}</span>";
    }
    String lozengeClass = config[0];
    String label = config[1];
    return "<span class=\"aui-lozenge aui-lozenge-subtle ${lozengeClass}\">${label}</span>";
}

String formatTimestamp(Long timestamp, String timeZoneId) {
    SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yy HH:mm")
    sdf.setTimeZone(TimeZone.getTimeZone(timeZoneId ?: "UTC"))
    return sdf.format(new Date(timestamp))
}