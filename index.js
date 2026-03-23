require("dotenv").config();

const JIRA_BASE_URL = process.env.JIRA_BASE_URL;
const JIRA_TOKEN = process.env.JIRA_TOKEN;
const DEFAULT_LIMIT = process.env.DEFAULT_LIMIT;
const DEFAULT_OFFSET = process.env.DEFAULT_OFFSET;
const PROPERTY_KEY = process.env.PROPERTY_KEY;
const OFFSET_STEP = process.env.OFFSET_STEP;

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

  if (response.status === 404) {
    return null;
  }

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

function extractIssueEntries(item) {
  // Returns array of { issueKey, component }
  const entries = [];

  for (const change of item.componentChanges || []) {
    const component = change.component;
    for (const assoc of change.associatedItems?.results || []) {
      if (assoc.typeName === "ISSUE") {
        entries.push({ issueKey: assoc.name, component });
      }
    }
    for (const changeItem of change.changeItems?.results || []) {
      if (changeItem.changeTo) {
        const match = changeItem.changeTo.match(/^[A-Z][A-Z0-9]+-\d+$/);
        if (match) {
          entries.push({ issueKey: changeItem.changeTo, component });
        }
      }
    }
  }

  return entries;
}

async function fetchAllAuditLogs() {
  const allItems = [];
  let offset = Number(DEFAULT_OFFSET);
  const limit = Number(DEFAULT_LIMIT);

  while (true) {
    console.log(`Fetching audit logs (offset=${offset}, limit=${limit})...`);
    const data = await fetchAuditLogs(limit, offset);
    allItems.push(...data.items);

    if (data.items.length < limit) break;
    offset += OFFSET_STEP;
  }

  return allItems;
}

async function main() {
  const allItems = await fetchAllAuditLogs();
  console.log(`Fetched ${allItems.length} total audit log items`);

  const executionItems = allItems.filter((item) => item.category !== "CONFIG_CHANGE");
  console.log(`Found ${executionItems.length} execution items, fetching details...`);

  // issueKey -> [{ ruleId, ruleName, timestamp, component }]
  const issueMap = {};

  for (const item of executionItems) {
    const detail = await fetchAuditLogItem(item.id);
    const issueEntries = extractIssueEntries(detail);

    for (const { issueKey, component } of issueEntries) {
      if (!issueMap[issueKey]) {
        issueMap[issueKey] = [];
      }

      const alreadyExists = issueMap[issueKey].some(
        (entry) => entry.ruleId === detail.objectItem.id && entry.timestamp === detail.created && entry.component === component
      );

      if (!alreadyExists) {
        issueMap[issueKey].push({
          ruleId: detail.objectItem.id,
          ruleName: detail.objectItem.name,
          timestamp: detail.created,
          component,
        });
      }
    }
  }

  const issueKeys = Object.keys(issueMap);
  console.log(`Found ${issueKeys.length} affected issues: ${issueKeys.join(", ")}`);

  for (const issueKey of issueKeys) {
    const newEntries = issueMap[issueKey];
    const existing = await getIssueProperty(issueKey);
    const existingEntries = existing?.value || [];

    const merged = [...existingEntries];
    for (const entry of newEntries) {
      const alreadyExists = merged.some(
        (e) => e.ruleId === entry.ruleId && e.timestamp === entry.timestamp && e.component === entry.component
      );
      if (!alreadyExists) {
        merged.push(entry);
      }
    }

    console.log(`Setting property "${PROPERTY_KEY}" on ${issueKey} (${existingEntries.length} existing + ${merged.length - existingEntries.length} new = ${merged.length} total)`);
    await setIssueProperty(issueKey, merged);
    console.log(`Done: ${issueKey}`);
  }

  console.log("Finished.");
}

main().catch(console.error);