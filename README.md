# Jira Automation Tracer

This middleware helps track Automation for Jira logs and link them to the issues.

> **ATTENTION!** This is using a non-documented method.

## API Endpoints

### Get Audit Log Items

```
GET /rest/cb-automation/latest/audit/GLOBAL
```

See the example response in [audit-log-items.json](audit-log-items.json).

### Get Audit Log Item

```
GET /rest/cb-automation/latest/audit/GLOBAL/item/{ID}
```

See the example response in [audit-log-item.json](audit-log-item.json).
