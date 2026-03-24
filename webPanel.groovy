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
    String htmlAutomationLogEntries = entries.collect { AutomationLogEntry entry ->
        StringBuilder htmlAutomationLogEntry = new StringBuilder();
        String categoryLozenge = getCategoryLozenge(entry.category);
        htmlAutomationLogEntry.append("<tr>")
        htmlAutomationLogEntry.append("<td><a href='${JIRA_BASE_URL}/secure/AutomationGlobalAdminAction!default.jspa#/rule/$entry.ruleId/audit-log'>$entry.ruleName</a></td>")
        htmlAutomationLogEntry.append("<td>${entry.component}</td>")
        htmlAutomationLogEntry.append("<td>${categoryLozenge}</td>")
        htmlAutomationLogEntry.append("<td>${formatTimestamp(entry.timestamp, userTimeZone)}</td>")
        htmlAutomationLogEntry.append("</tr>")
        return htmlAutomationLogEntry.toString();
    }.join("\n");
    html.append(htmlAutomationLogEntries)
    html.append("</table>")
    writer.write("${html}");
}

String getCategoryLozenge(String category) {
    switch (category) {
        case "SUCCESS":
            return "<span class=\"aui-lozenge aui-lozenge-success\"><span class=\"aui-icon aui-icon-small aui-iconfont-approve\" role=\"img\"></span></span>";
        case "FAILURE":
            return "<span class=\"aui-lozenge aui-lozenge-removed\"><span class=\"aui-icon aui-icon-small aui-iconfont-cross-circle\" role=\"img\"></span></span>";
        case "SOME_ERRORS":
            return "<span class=\"aui-lozenge aui-lozenge-removed\"><span class=\"aui-icon aui-icon-small aui-iconfont-cross-circle\" role=\"img\"></span></span>";
        case "NO_ACTIONS_PERFORMED":
            return "<span class=\"aui-lozenge aui-lozenge-success\"><span class=\"aui-icon aui-icon-small aui-iconfont-vid-forward\" role=\"img\"></span></span>"
        default:
            return "";
    }
}

String formatTimestamp(Long timestamp, String timeZoneId) {
    SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yy HH:mm")
    sdf.setTimeZone(TimeZone.getTimeZone(timeZoneId ?: "UTC"))
    return sdf.format(new Date(timestamp))
}