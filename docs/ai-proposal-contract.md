# AI Proposal Contract v1

Endpoint: `POST /v1/ai/propose`

The Gateway translates natural-language intent into a provider-independent proposal. It never executes a planning command, writes to Room, or owns the authoritative PlanningEngine decision.

## Request

```json
{
  "schemaVersion": "1",
  "message": "Quero estudar francês amanhã por uma hora.",
  "context": {
    "nowIso": "2026-09-14T10:00:00-03:00",
    "activeActivityId": null,
    "minimalRouteFacts": []
  }
}
```

## Response

```json
{
  "schemaVersion": "1",
  "requestId": "a-request-id",
  "model": "provider-model",
  "proposal": {
    "commandType": "CREATE_ACTIVITY_DRAFT",
    "explanation": "A solicitação contém título, data relativa e duração.",
    "requiresConfirmation": true,
    "payload": {
      "title": "Estudar francês",
      "date": "2026-09-15",
      "durationMinutes": 60
    }
  }
}
```

## Supported command types

- `CREATE_ACTIVITY_DRAFT`: creates a proposal for a new activity; app validation is authoritative.
- `EXPLAIN_NEXT_ACTIVITY`: explains an activity using supplied evidence only.
- `REORGANIZE_DAY`: proposes a delay; the app recalculates the day.
- `MISSING_INFORMATION`: explicitly asks the app to collect required fields.
- `RECALCULATE_ROUTE`: asks the app to run its deterministic route calculation.

Invalid schema versions, unsupported commands, missing required payload fields, malformed JSON, and other structurally invalid model output are rejected by the Gateway. Provider-specific types never cross this API boundary.
