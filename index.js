require("dotenv").config();

const JIRA_BASE_URL = process.env.JIRA_BASE_URL;
const JIRA_TOKEN = process.env.JIRA_TOKEN;
const DEFAULT_LIMIT = process.env.DEFAULT_LIMIT;
const DEFAULT_OFFSET = process.env.DEFAULT_OFFSET;
const PROPERTY_KEY = process.env.PROPERTY_KEY;

async function fetchAuditLogs(limit = DEFAULT_LIMIT, offset = DEFAULT_OFFSET) {
  const url = `${JIRA_BASE_URL}/rest/cb-automation/latest/audit/GLOBAL?limit=${limit}&offset=${offset}`;

  const response = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${JIRA_TOKEN}`,
      Accept: "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch audit logs: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

async function fetchAuditLogItem(id) {
  const url = `${JIRA_BASE_URL}/rest/cb-automation/latest/audit/GLOBAL/item/${id}`;

  const response = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${JIRA_TOKEN}`,
      Accept: "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch audit log item ${id}: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

async function getIssueProperty(issueIdOrKey) {
  const url = `${JIRA_BASE_URL}/rest/api/2/issue/${issueIdOrKey}/properties/${PROPERTY_KEY}`;

  const response = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${JIRA_TOKEN}`,
      Accept: "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to get property "${PROPERTY_KEY}" from ${issueIdOrKey}: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

async function setIssueProperty(issueIdOrKey, value) {
  const url = `${JIRA_BASE_URL}/rest/api/2/issue/${issueIdOrKey}/properties/${PROPERTY_KEY}`;

  const response = await fetch(url, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${JIRA_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(value),
  });

  if (!response.ok) {
    throw new Error(`Failed to set property "${PROPERTY_KEY}" on ${issueIdOrKey}: ${response.status} ${response.statusText}`);
  }
}

function extractIssueKeys(item) {
  const keys = new Set();

  for (const change of item.componentChanges || []) {
    for (const assoc of change.associatedItems?.results || []) {
      if (assoc.typeName === "ISSUE") {
        keys.add(assoc.name);
      }
    }
    for (const changeItem of change.changeItems?.results || []) {
      if (changeItem.changeTo) {
        const match = changeItem.changeTo.match(/^[A-Z][A-Z0-9]+-\d+$/);
        if (match) {
          keys.add(changeItem.changeTo);
        }
      }
    }
  }

  return keys;
}

async function main() {
  const data = await fetchAuditLogs();
  console.log(`Fetched ${data.items.length} audit log items`);

  const executionItems = data.items.filter((item) => item.category !== "CONFIG_CHANGE");
  console.log(`Found ${executionItems.length} execution items, fetching details...`);

  // issueKey -> [{ ruleId, ruleName, timestamp }]
  const issueMap = {};

  for (const item of executionItems) {
    const detail = await fetchAuditLogItem(item.id);
    const issueKeys = extractIssueKeys(detail);

    for (const key of issueKeys) {
      if (!issueMap[key]) {
        issueMap[key] = [];
      }

      const alreadyExists = issueMap[key].some(
        (entry) => entry.ruleId === detail.objectItem.id && entry.timestamp === detail.created
      );

      if (!alreadyExists) {
        issueMap[key].push({
          ruleId: detail.objectItem.id,
          ruleName: detail.objectItem.name,
          timestamp: detail.created,
        });
      }
    }
  }

  const issueKeys = Object.keys(issueMap);
  console.log(`Found ${issueKeys.length} affected issues: ${issueKeys.join(", ")}`);

  for (const issueKey of issueKeys) {
    const propertyValue = issueMap[issueKey];
    console.log(`Setting property "${PROPERTY_KEY}" on ${issueKey} (${propertyValue.length} entries)`);
    await setIssueProperty(issueKey, propertyValue);
    console.log(`Done: ${issueKey}`);
  }

  console.log("Finished.");
}

main().catch(console.error);