import groovy.json.JsonSlurper
import groovy.transform.Canonical
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.component.ComponentAccessor
import com.adaptavist.hapi.jira.properties.EntityProperties

@Canonical
class AutomationLogEntry {
    Integer ruleId;
    String ruleName;
    Long timestamp;
}

final String PROPERTY_KEY = "com.troshin.jira.automation.tracer";
final String JIRA_BASE_URL = ComponentAccessor.applicationProperties.getJiraBaseUrl()

Issue issue = context.get("issue") as Issue;
if (issue) {
    EntityProperties issueProperties = issue.getEntityPropertiesOverrideSecurity();
    String automationTracerPropertyValue = issueProperties.getJson(PROPERTY_KEY);

    List<AutomationLogEntry> entries = new JsonSlurper()
            .parseText(automationTracerPropertyValue)
            .collect { Map it ->
                new AutomationLogEntry(
                        ruleId: it.ruleId as Integer,
                        ruleName: it.ruleName as String,
                        timestamp: it.timestamp as Long
                );
            };

    String html = entries.collect { AutomationLogEntry entry ->
        "<li><a href='${JIRA_BASE_URL}/secure/AutomationGlobalAdminAction!default.jspa#/rule/$entry.ruleId/audit-log'>$entry.ruleName</a> at ${new Date(entry.timestamp)}</li>"
    }.join("\n");
    writer.write("${html}");
}