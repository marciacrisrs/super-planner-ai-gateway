# Gateway issue contracts

## GW #2 — Provider-independent AI Proposal

Source: `super-planner-app#350`.

Define versioned request/response contracts for AI proposals. Contracts must not expose Gemini-specific types or fields. Responses must be structurally validated before returning to clients. The contract represents a proposal only; the Android domain validates and applies it.

Acceptance:
- versioned public contract;
- provider-neutral DTOs;
- schema validation;
- invalid model output rejected safely;
- no persistence or domain mutation in Gateway;
- contract documented with examples;
- compatibility tests.

## GW #3 — Security, authentication and limits

Secure every non-health endpoint. Do not expose a provider proxy or provider credentials. Establish authentication, request-size limits, rate limiting, timeouts and safe error responses. Secrets remain server-side.

Acceptance:
- unauthenticated AI calls rejected;
- provider credentials never returned/logged;
- payload and prompt limits enforced;
- rate limits enforced;
- upstream timeout enforced;
- errors do not leak provider internals;
- health endpoints remain usable for platform probes;
- security tests cover the boundary.

## GW #4 — Observability and AI error handling

Introduce correlation/request IDs, structured logs and metrics for latency, success/failure, model and upstream errors. Never log prompts or sensitive user context by default.

Acceptance:
- every AI request has a correlation ID;
- response exposes correlation ID where appropriate;
- latency measured;
- provider/model captured in telemetry;
- failures categorized;
- logs are structured;
- sensitive payloads are excluded/redacted;
- upstream failures map to stable API errors;
- tests cover error mapping.

## GW #5 — Natural Language → AiProposal

Source: `super-planner-app#250`.

Accept natural-language planning intent and produce a provider-independent `AiProposal`. Extract only information supported by the input/context; represent ambiguity or missing required information explicitly. Do not persist inferred data or execute use cases.

Acceptance:
- dates, times, duration and recurrence are structured;
- ambiguity is represented rather than guessed silently;
- missing required information can be requested by the app;
- proposal is schema-valid;
- provider-specific response never reaches the app;
- no domain mutation;
- deterministic contract tests cover representative prompts.

## GW #6 — Contextual Explanation

Source: `super-planner-app#251`.

Generate concise explanations from structured PlanningEngine evidence. The Gateway must not calculate scheduling decisions or invent reasons.

Acceptance:
- input consists of structured evidence;
- explanation is grounded only in supplied evidence;
- insufficient evidence produces an explicit uncertainty response;
- no invented motivation/psychology;
- response has a stable contract;
- tests verify grounding behavior.

## GW #7 — Natural Language → PlanningCommand

Source: `super-planner-app#252`.

Translate user requests for changes into structured, provider-independent commands. Commands are proposals for the app's use cases, never direct database operations.

Acceptance:
- supported intents map to typed commands;
- required fields are explicit;
- ambiguity requires clarification;
- unsupported intents are rejected safely;
- no Room/database access;
- no route calculation;
- schema validation and tests exist.

## GW #8 — Planning Insights

Sources: `super-planner-app#258`, `#353`, `#354`.

Consume structured execution history and capacity facts supplied by the app and generate evidence-based planning insights. The Gateway must not become the capacity engine.

Acceptance:
- evidence is structured;
- insufficient data is recognized;
- insights cite supplied evidence IDs/fields where possible;
- no silent changes to commitments or planning rules;
- capacity calculations remain domain-owned;
- suggestions have stable structured output;
- tests cover insufficient, low, medium and strong evidence.

## GW #9 — Preference Inference

Sources: `super-planner-app#253`, `#355`.

Infer candidate planning preferences from structured evidence. A candidate is not a persisted preference. The app owns storage, editing, revocation and governance.

Acceptance:
- candidate preferences include evidence and confidence;
- insufficient evidence does not produce strong preferences;
- observed behavior and inferred preference are distinct;
- provider-neutral schema;
- no persistence;
- explanations can identify why a candidate was inferred;
- tests cover confidence and contradictory evidence.

## GW #10 — Scenario Interpretation

Source: `super-planner-app#356`.

Interpret natural-language what-if requests into structured scenario changes. The app runs the scenario through the real PlanningEngine; the Gateway does not simulate or mutate the real plan.

Acceptance:
- scenario changes are typed;
- scenario is explicitly marked hypothetical;
- no persistence or real-plan mutation;
- ambiguity is surfaced;
- unsupported scenarios are safely rejected;
- contract tests exist.

## GW #11 — Next Action Recommendation

Source: `super-planner-app#357`.

Generate a contextual recommendation from structured current-state facts and PlanningEngine constraints. Feasibility remains domain-owned.

Acceptance:
- input includes available time, upcoming commitments, priorities and relevant logistics;
- recommendation cannot override supplied hard constraints;
- rationale is grounded in evidence;
- multiple relevant alternatives remain limited and structured;
- Gateway does not calculate the authoritative route;
- recommendation is provider-independent;
- tests cover short windows, conflicts and alternatives.
