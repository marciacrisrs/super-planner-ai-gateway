# Super Planner AI Gateway

Ktor service that keeps LLM credentials and provider access outside the Android app.

## Architecture

`Android → AI Gateway → LLM Provider`

The gateway exposes versioned AI endpoints and returns provider-independent structured contracts. The Android app does not access the provider directly and does not receive provider credentials.

## Endpoints

- `GET /health` — liveness/process health.
- `GET /ready` — readiness based on required gateway/provider configuration.
- `POST /v1/ai/generate` — provider-backed text generation.
- `POST /v1/ai/propose` — provider-independent `AiProposal` contract.
- `POST /v1/ai/organize-week` — structured week-organization proposal.
- `POST /v1/ai/natural-language` — natural language to structured proposal interpretation.
- `POST /v1/ai/explain` — evidence-grounded explanation.
- `POST /v1/ai/command` — natural language to safe `PlanningCommand` interpretation.
- `POST /v1/ai/insights` — planning insights from supplied evidence.
- `POST /v1/ai/preferences` — preference inference from observations.
- `POST /v1/ai/scenario` — hypothetical scenario interpretation.
- `POST /v1/ai/next-action` — next-action recommendation constrained to domain-provided candidates.

All AI routes return `X-Request-Id` and use schema version `1` for structured contracts.

## Configuration

- `GOOGLE_CLOUD_PROJECT` — required Google Cloud project.
- `GOOGLE_CLOUD_LOCATION` — required provider location in production.
- `GEMINI_MODEL` — required model in production.
- `GATEWAY_API_KEY` — required gateway credential in production.
- `GATEWAY_RATE_LIMIT` — requests per minute per credential; defaults to `60`.
- `AI_TIMEOUT_MS` — AI operation timeout in milliseconds; defaults to `30000` and is clamped to 1–120 seconds.
- `ENVIRONMENT` — use `test` only for automated tests.

Provider authentication uses the server's Google Cloud identity/ADC. The gateway API credential is separate from the provider credential.

### Security note

The current API-key layer is an MVP access-control boundary. A static gateway key must **not** be embedded in a public Android APK because it can be extracted. For a public mobile client, replace or augment it with user/device authentication and a server-verifiable token before treating the endpoint as production-grade authorization.

Request bodies and capability lists are bounded before provider generation. Provider operations are time-limited, and malformed provider output is rejected before it reaches the Android client.

The rate limiter is in-memory and therefore scoped to a single Cloud Run instance. It is a protection layer, not a distributed quota system.

## Observability

Structured logs expose request ID, capability, model, total request latency and categorized failures. Provider calls additionally record provider latency and model. Prompts, generated responses, credentials and other sensitive request data are not logged.

## Contract and domain boundary

The gateway interprets, structures and explains. It never becomes the source of truth for planning feasibility.

The Android `PlanningEngine` remains responsible for deterministic planning rules, authoritative validation and application of changes. The gateway does not access Room and does not mutate planner state.

Mutating AI proposals/commands must carry explicit confirmation semantics. The gateway validates the response shape and safety boundary before returning it to the app.

For `organize-week`, the gateway additionally grounds the provider response in the supplied domain context: summaries must reconcile with request facts, existing/fixed/desire proposals must reuse domain IDs and titles, fixed commitments must be preserved, and invalid logistics references are rejected before provider generation.

## Local tests

```bash
gradle test --no-daemon
```

CI runs the same command with Java 21 and test environment variables.
