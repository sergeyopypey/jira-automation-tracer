# Jira Automation Tracer

This middleware helps track Automation for Jira logs and link them to the issues.

> **ATTENTION!** This is using a non-documented method.

## How It Works

1. Fetches automation audit logs from Jira (paginated)
2. For each execution log, fetches detailed item to extract affected issues
3. Groups entries by issue key with deduplication
4. Merges new entries with existing issue properties (without overwriting)
5. Tracks the last processed audit log ID to avoid reprocessing

## Setup

### Prerequisites

- Node.js
- Jira instance with Automation for Jira

### Installation

```bash
npm install
```

### Configuration

Create a `.env` file:

```env
JIRA_BASE_URL=https://your-jira-instance.com
JIRA_TOKEN=your-personal-access-token
DEFAULT_LIMIT=100
DEFAULT_OFFSET=0
OFFSET_STEP=100
PROPERTY_KEY=com.troshin.jira.automation.tracer
```

## Usage

### Process new audit logs

```bash
npm start
```

Fetches new automation audit logs since the last run, extracts affected issues, and writes automation trace data as an issue property.

### Flush all issue properties

```bash
npm run flush
```

Removes the automation tracer property from all affected issues and resets the last processed state. Use this to clean up and start fresh.

## Issue Property Format

Each affected issue gets a JSON property (`PROPERTY_KEY`) containing an array of automation log entries:

```json
[
    {
        "ruleId": 10,
        "ruleName": "test",
        "timestamp": 1768461846113,
        "component": "TRIGGER",
        "category": "SUCCESS"
    }
]
```

| Field       | Description                                                  |
| ----------- | ------------------------------------------------------------ |
| `ruleId`    | Automation rule ID                                           |
| `ruleName`  | Automation rule name                                         |
| `timestamp` | Execution timestamp (epoch ms)                               |
| `component` | Component type: `TRIGGER`, `BRANCH`, `ACTION`, etc.          |
| `category`  | Execution result: `SUCCESS`, `SOME_ERRORS`, `CONFIG_CHANGE`, etc. |

See full example in [audit-log-property.json](audit-log-property.json).

## Web Panel (ScriptRunner)

The `webPanel.groovy` script renders automation trace data on the Jira issue view. It deserializes the issue property into `AutomationLogEntry` objects and displays them as a list with links to the automation rule audit log.

## API Endpoints (Non-Documented)

### Get Audit Log Items

```
GET /rest/cb-automation/latest/audit/GLOBAL?limit={limit}&offset={offset}
```

See example response in [audit-log-items.json](audit-log-items.json).

### Get Audit Log Item

```
GET /rest/cb-automation/latest/audit/GLOBAL/item/{ID}
```

See example response in [audit-log-item.json](audit-log-item.json).

### Get Issue Property

```
GET /rest/api/2/issue/{issueIdOrKey}/properties/{propertyKey}
```

### Set Issue Property

```
PUT /rest/api/2/issue/{issueIdOrKey}/properties/{propertyKey}
```

### Delete Issue Property

```
DELETE /rest/api/2/issue/{issueIdOrKey}/properties/{propertyKey}
```
