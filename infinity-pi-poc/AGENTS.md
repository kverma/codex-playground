# Integration PoC agent guide

This is an isolated experiment under `infinity-pi-poc/`. It does not change RevealSwift or other workspaces.

## Invariants

- Infinity owns Infinity conversations and RAP callback routing; Pi Durable owns delegated Pi task state. Never share internal transcript databases.
- The gateway's SQLite journal is the authority for accepted RAP invocations and callback delivery. ACK only after committing the job.
- Pi Durable task IDs are deduplicated in its own **atomic** document+task commit; the gateway and Pi have separate SQLite files.
- Callback delivery is **at least once**; do not claim exactly once. A callback response might be lost after Infinity receives the message.
- Chord is a live, lossy UI projection, NEVER the business event log.
- Only loopback callback URLs by default. Network expansion requires authentication, allowlists, HTTPS, proper service identity and audit.
- No hosted LLM, Cloudflare, Docker or AWS is needed for CI. Do not commit credentials.
- Do not add side-effecting operations before enforcing external idempotency and reconciliation.

## Workflows

- `npm install` (Node >=22.19), then `npm test` and `npm run typecheck`.
- `npm start` starts the real Pi Durable worker + Chord projection + RAP listener, not a mock worker.
- `POC_EXECUTOR=fixture npm start` runs only the dependency-free deterministic worker for local diagnosis.
- `GET /.well-known/rap-toolset` advertises `analyze_ci_failures`. `POST /` commits invocation and ACKs.
- `POST /cancel_tool_call` handles advisory cancellation.
- `.infinity/rap.json` is a local example; use it in the directory where Infinity Code runs.

## Verification boundaries

- `tests/core.test.mjs`: actual HTTP + SQLite + retry/recovery; fake deterministic worker.
- `tests/integration.test.ts`: real installed Chord + Pi Durable packages; no inference.
- Real Infinity Code daemon and production auth/SSO/mTLS are separate acceptance gates and NOT proven by package tests.
