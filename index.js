const fs = require("fs");
const path = require("path");
require("dotenv").config();

const LAST_RUN_FILE = path.join(__dirname, "last-run.json");
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

async function deleteIssueProperty(issueIdOrKey) {
  const url = `${JIRA_BASE_URL}/rest/api/2/issue/${issueIdOrKey}/properties/${PROPERTY_KEY}`;

  const response = await fetch(url, {
    method: "DELETE",
    headers: {
      Authorization: `Bearer ${JIRA_TOKEN}`,
    },
  });

  if (response.status === 404) {
    return false;
  }

  if (!response.ok) {
    throw new Error(`Failed to delete property "${PROPERTY_KEY}" from ${issueIdOrKey}: ${response.status} ${response.statusText}`);
  }

  return true;
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

function getLastProcessedId() {
  try {
    const data = JSON.parse(fs.readFileSync(LAST_RUN_FILE, "utf-8"));
    return data.lastProcessedId || 0;
  } catch {
    return 0;
  }
}

function saveLastProcessedId(id) {
  fs.writeFileSync(LAST_RUN_FILE, JSON.stringify({ lastProcessedId: id }, null, 2));
}

async function fetchAllAuditLogs(lastProcessedId) {
  const allItems = [];
  let offset = Number(DEFAULT_OFFSET);
  const limit = Number(DEFAULT_LIMIT);
  let reachedProcessed = false;

  while (!reachedProcessed) {
    console.log(`Fetching audit logs (offset=${offset}, limit=${limit})...`);
    const data = await fetchAuditLogs(limit, offset);

    for (const item of data.items) {
      if (item.id <= lastProcessedId) {
        reachedProcessed = true;
        break;
      }
      allItems.push(item);
    }

    if (data.items.length < limit) break;
    offset += Number(OFFSET_STEP);
  }

  return allItems;
}

async function main() {
  const lastProcessedId = getLastProcessedId();
  console.log(`Last processed audit log ID: ${lastProcessedId}`);

  const allItems = await fetchAllAuditLogs(lastProcessedId);
  console.log(`Fetched ${allItems.length} new audit log items`);

  if (allItems.length === 0) {
    console.log("No new audit logs to process.");
    return;
  }

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
          category: detail.category,
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

  const maxId = Math.max(...allItems.map((item) => item.id));
  saveLastProcessedId(maxId);
  console.log(`Finished. Saved last processed ID: ${maxId}`);
}

async function flush() {
  const allItems = await fetchAllAuditLogs(0);
  console.log(`Fetched ${allItems.length} total audit log items`);

  const executionItems = allItems.filter((item) => item.category !== "CONFIG_CHANGE");
  const issueKeys = new Set();

  for (const item of executionItems) {
    const detail = await fetchAuditLogItem(item.id);
    const issueEntries = extractIssueEntries(detail);
    for (const { issueKey } of issueEntries) {
      issueKeys.add(issueKey);
    }
  }

  console.log(`Found ${issueKeys.size} issues to flush: ${[...issueKeys].join(", ")}`);

  for (const issueKey of issueKeys) {
    const deleted = await deleteIssueProperty(issueKey);
    console.log(`${issueKey}: ${deleted ? "deleted" : "not found"}`);
  }

  if (fs.existsSync(LAST_RUN_FILE)) {
    fs.unlinkSync(LAST_RUN_FILE);
    console.log("Removed last-run.json");
  }

  console.log("Flush complete.");
}

const command = process.argv[2];
if (command === "flush") {
  flush().catch(console.error);
} else {
  main().catch(console.error);
}