import groovy.json.JsonOutput
import groovy.transform.Field
import org.apache.log4j.Logger
import groovy.json.JsonSlurper
import com.troshin.utils.HttpHelper
import com.troshin.utils.SecretsHolder
import com.adaptavist.hapi.jira.issues.Issues
import com.atlassian.jira.component.ComponentAccessor
import com.onresolve.scriptrunner.runner.ScriptRunnerImpl
import com.atlassian.sal.api.pluginsettings.PluginSettings
import com.adaptavist.hapi.jira.properties.EntityProperties
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory

@Field final int OFFSET_STEP = 100;
@Field final int DEFAULT_OFFSET = 0;
@Field final int DEFAULT_LIMIT = 100;
@Field final int MAX_ITEMS_PER_RUN = 1000;
@Field final String PROPERTY_KEY = "com.troshin.jira.automation.tracer";
@Field final Logger logger = Logger.getLogger("com.troshin.jira.automation.tracer.cronJob");
@Field final String JIRA_BASE_URL = ComponentAccessor.getApplicationProperties().getJiraBaseUrl();

@Field HttpHelper httpHelper = new HttpHelper();
@Field JsonSlurper jsonSlurper = new JsonSlurper();
@Field final Map<String, String> AUTH_HEADERS = ["Authorization": "Bearer " + SecretsHolder.getJiraToken(), "Accept": "application/json"];

@Field final String SETTINGS_KEY_LAST_PROCESSED_ID = "com.troshin.jira.automation.tracer.lastProcessedId";
@Field PluginSettingsFactory pluginSettingsFactory = ScriptRunnerImpl.getOsgiService(PluginSettingsFactory);
@Field PluginSettings pluginSettings = pluginSettingsFactory.createGlobalSettings();

// --- Last Run ID storage ---

Long getLastProcessedId() {
    String value = pluginSettings.get(SETTINGS_KEY_LAST_PROCESSED_ID) as String;
    if (value == null || value.isEmpty()) {
        return 0L;
    }
    return Long.parseLong(value);
}

void saveLastProcessedId(Long id) {
    pluginSettings.put(SETTINGS_KEY_LAST_PROCESSED_ID, id.toString());
}

// --- REST API methods ---

Map fetchAuditLogs(int limit, int offset) {
    String url = "${JIRA_BASE_URL}/rest/cb-automation/latest/audit/GLOBAL?limit=${limit}&offset=${offset}";
    String responseBody = httpHelper.GET(url, AUTH_HEADERS);
    return jsonSlurper.parseText(responseBody) as Map;
}

Map fetchAuditLogItem(Long id) {
    String url = "${JIRA_BASE_URL}/rest/cb-automation/latest/audit/GLOBAL/item/${id}";
    String responseBody = httpHelper.GET(url, AUTH_HEADERS);
    return jsonSlurper.parseText(responseBody) as Map;
}

List<Map> getIssueProperty(String issueIdOrKey) {
    try {
        EntityProperties properties = Issues.getByKey(issueIdOrKey).getEntityPropertiesOverrideSecurity();
        String json = properties.getJson(PROPERTY_KEY);

        if (json == null || json.isEmpty()) {
            return null;
        }

        return jsonSlurper.parseText(json) as List<Map>;
    } catch (com.adaptavist.hapi.jira.issues.exceptions.IssueRetrievalException e) {
        logger.warn("Issue ${issueIdOrKey} no longer exists, skipping: ${e.message}");
        return null;
    }
}

void setIssueProperty(String issueIdOrKey, List<Map> value) {
    try {
        EntityProperties properties = Issues.getByKey(issueIdOrKey).getEntityPropertiesOverrideSecurity();
        String json = JsonOutput.toJson(value);
        properties.setJson(PROPERTY_KEY, json);
    } catch (com.adaptavist.hapi.jira.issues.exceptions.IssueRetrievalException e) {
        logger.warn("Issue ${issueIdOrKey} no longer exists, skipping property update: ${e.message}");
    }
}

// void deleteIssueProperty(String issueIdOrKey) {
//     EntityProperties properties = Issues.getByKey(issueIdOrKey).getEntityPropertiesOverrideSecurity();
//     properties.setJson(PROPERTY_KEY, null);
// }

// --- Processing logic ---

List<Map> extractIssueEntries(Map item) {
    List<Map> entries = new ArrayList<>();
    List<Map> componentChanges = item.get("componentChanges") as List<Map>;

    if (componentChanges == null) {
        return entries;
    }

    for (Map change : componentChanges) {
        String component = change.get("component") as String;

        Map associatedItems = change.get("associatedItems") as Map;
        if (associatedItems != null) {
            List<Map> results = associatedItems.get("results") as List<Map>;
            if (results != null) {
                for (Map assoc : results) {
                    if ("ISSUE".equals(assoc.get("typeName"))) {
                        entries.add([issueKey: assoc.get("name"), component: component]);
                    }
                }
            }
        }

        Map changeItems = change.get("changeItems") as Map;
        if (changeItems != null) {
            List<Map> changeResults = changeItems.get("results") as List<Map>;
            if (changeResults != null) {
                for (Map changeItem : changeResults) {
                    String changeTo = changeItem.get("changeTo") as String;
                    if (changeTo != null && changeTo.matches("^[A-Z][A-Z0-9]+-\\d+\$")) {
                        entries.add([issueKey: changeTo, component: component]);
                    }
                }
            }
        }
    }

    return entries;
}

Long fetchLatestAuditLogId() {
    Map data = fetchAuditLogs(1, 0);
    List<Map> items = data.get("items") as List<Map>;

    if (items == null || items.isEmpty()) {
        return 0L;
    }

    return items.get(0).get("id") as Long;
}

List<Map> fetchAllAuditLogs(Long lastProcessedId) {
    List<Map> allItems = new ArrayList<>();
    int offset = DEFAULT_OFFSET;
    boolean reachedProcessed = false;
    boolean reachedLimit = false;

    while (!reachedProcessed && !reachedLimit) {
        Map data = fetchAuditLogs(DEFAULT_LIMIT, offset);
        List<Map> items = data.get("items") as List<Map>;

        for (Map item : items) {
            Long itemId = item.get("id") as Long;
            if (itemId <= lastProcessedId) {
                reachedProcessed = true;
                break;
            }
            allItems.add(item);

            if (allItems.size() >= MAX_ITEMS_PER_RUN) {
                logger.warn("Reached max items per run limit (${MAX_ITEMS_PER_RUN}). Older unprocessed items will be skipped.");
                reachedLimit = true;
                break;
            }
        }

        if (items.size() < DEFAULT_LIMIT) {
            break;
        }
        offset += OFFSET_STEP;
    }

    logger.info("Fetched audit logs up to offset=${offset}, collected ${allItems.size()} items");
    return allItems;
}

void execute() {
    Long lastProcessedId = getLastProcessedId();
    logger.info("Last processed audit log ID: ${lastProcessedId}");

    if (lastProcessedId == 0L) {
        Long latestId = fetchLatestAuditLogId();
        logger.info("Cold start detected. Saving current latest audit log ID: ${latestId}. Processing will start from next run.");
        saveLastProcessedId(latestId);
        return;
    }

    List<Map> allItems = fetchAllAuditLogs(lastProcessedId);
    logger.info("Fetched ${allItems.size()} new audit log items");

    if (allItems.isEmpty()) {
        logger.info("No new audit logs to process.");
        return;
    }

    List<String> excludedCategories = ["CONFIG_CHANGE", "NO_ACTIONS_PERFORMED"];
    List<Map> executionItems = allItems.findAll { item ->
        !excludedCategories.contains(item.get("category"))
    };
    logger.info("Found ${executionItems.size()} execution items, fetching details...");

    // issueKey -> List<Map>
    Map<String, List<Map>> issueMap = new LinkedHashMap<>();

    for (Map item : executionItems) {
        Long itemId = item.get("id") as Long;
        Map detail = fetchAuditLogItem(itemId);
        List<Map> issueEntries = extractIssueEntries(detail);

        Map objectItem = detail.get("objectItem") as Map;
        Long ruleId = objectItem.get("id") as Long;
        String ruleName = objectItem.get("name") as String;
        Long timestamp = detail.get("created") as Long;
        String category = detail.get("category") as String;

        for (Map entry : issueEntries) {
            String issueKey = entry.get("issueKey") as String;
            String component = entry.get("component") as String;

            if (!issueMap.containsKey(issueKey)) {
                issueMap.put(issueKey, new ArrayList<>());
            }

            List<Map> existingEntries = issueMap.get(issueKey);
            boolean alreadyExists = existingEntries.any { e ->
                e.get("ruleId") == ruleId && e.get("timestamp") == timestamp && e.get("component") == component
            };

            if (!alreadyExists) {
                existingEntries.add([
                    ruleId   : ruleId,
                    ruleName : ruleName,
                    timestamp: timestamp,
                    component: component,
                    category : category
                ]);
            }
        }
    }

    Set<String> issueKeys = issueMap.keySet();
    logger.info("Found ${issueKeys.size()} affected issues");

    int updatedCount = 0;
    int skippedCount = 0;

    for (String issueKey : issueKeys) {
        List<Map> newEntries = issueMap.get(issueKey);
        List<Map> existingEntries = getIssueProperty(issueKey);
        if (existingEntries == null) {
            existingEntries = new ArrayList<>();
        }

        List<Map> merged = new ArrayList<>(existingEntries);
        for (Map entry : newEntries) {
            boolean alreadyExists = merged.any { e ->
                e.get("ruleId") == entry.get("ruleId") &&
                e.get("timestamp") == entry.get("timestamp") &&
                e.get("component") == entry.get("component")
            };
            if (!alreadyExists) {
                merged.add(entry);
            }
        }

        int addedCount = merged.size() - existingEntries.size();
        if (addedCount > 0) {
            setIssueProperty(issueKey, merged);
            updatedCount++;
        } else {
            skippedCount++;
        }
    }

    logger.info("Updated ${updatedCount} issues, skipped ${skippedCount} (no new entries)");

    Long maxId = allItems.collect { item -> item.get("id") as Long }.max();
    saveLastProcessedId(maxId);
    logger.info("Finished. Saved last processed ID: ${maxId}");
}

// --- Execute ---

execute();