# Super Planner AI Gateway

Ktor service that keeps LLM credentials and provider access outside the Android app.

## Architecture

`Android → AI Gateway → LLM Provider`

The gateway exposes versioned AI endpoints and returns structured contracts where the product requires them. The Android app does not access the provider directly and does not receive provider credentials.

## Endpoints

- `GET /health` — process health.
- `GET /ready` — readiness probe.
- `POST /v1/ai/generate` — provider-backed text generation.
- `POST /v1/ai/organize-week` — structured week-organization proposal.

## Configuration

- `GOOGLE_CLOUD_PROJECT` — required Google Cloud project.
- `GOOGLE_CLOUD_LOCATION` — optional Vertex AI location; defaults to `us-central1`.
- `GEMINI_MODEL` — optional model; defaults to `gemini-2.5-flash`.

Authentication to the provider uses the server's Google Cloud identity/ADC. No provider API key is stored in the Android application.

## Product boundary

The gateway interprets and proposes. The Android domain remains responsible for deterministic planning rules, validation and application of changes. The gateway does not access Room and does not mutate planner state.
