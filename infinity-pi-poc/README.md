# Infinity × Chord × Pi Durable — integration PoC

An intentionally small, **locally gateway-tested RAP server** that delegates Infinity tool invocations to genuine Pi Durable custom tasks. Chord exposes a live, typed status projection. Nothing from Infinity's Rust runtime is ported.

## Run

Requires Node >=22.19 (Node 24 recommended):

```bash
npm install
npm test
npm run typecheck
npm start
```

Install/use Infinity Code separately, then point its project `.infinity/rap.json` to `http://127.0.0.1:3001` (sample included). Start Infinity from a project containing that config. The PoC does **not** start the Infinity daemon or install its binaries.

Discovery:

```bash
curl http://127.0.0.1:3001/.well-known/rap-toolset
```

Send a test invocation only if you have an HTTP callback receiver on `127.0.0.1:4002`; the gateway will reject callbacks to unknown origins. The field names and `tool_result` envelope conform to Infinity's published [RAP server guide](https://infinity.hydro.run/docs/rap/using-rap/building-a-rap-tool/).

```json
{
  "operation":"analyze_ci_failures",
  "id":"ci-1",
  "group_id":"thread-1",
  "call_id":null,
  "arguments":{"failures":["DB timeout","connection reset","assertion failed"]},
  "callback_url":"http://127.0.0.1:4002/callback"
}
```

The result is a machine-readable JSON string inside a RAP `tool_result` callback. A real Infinity runtime supplies its own `id`, `group_id`, and `callback_url`; the above is an illustration, not a manually constructed production call.

## Components

```text
                   Chord object (live status projection)
                                  ↑
Infinity Rust/Code --RAP--> HTTP gateway ── SQLite journal
                                  │          jobs/outbox
                                  ↓
                      Pi Durable task worker
                       SQLite conversation
                                  │
                                  └─ result → RAP callback outbox → Infinity
```

`src/gateway.mjs`: RAP manifest, validation, commit-before-ACK, idempotency and callback dispatcher.

`src/store.mjs`: SQLite WAL+FULL sync, atomic job/outbox transaction, leases, stale-owner fencing, retry scheduling.

`src/pi-executor.ts`: installed Pi Durable 1.4 custom task with an atomic task ledger keyed by RAP job ID. Exactly one logical Pi task per job is created even if the gateway process is restarted after spawning a task but before recording its result.

`src/chord-control.ts`: Chord 1.4 remote object in a local host with live status subscribers. Demonstrates typed state and methods without pretending Chord is persistent. The PoC doesn't expose a remote Chord socket or enforce per-principal UI authorization.

`src/analysis.mjs`: deliberately deterministic CI failure classifier, free of credentials and external side effects, for repeatable restart tests.

## Durability contract

1. Every accepted RAP request has a committed gateway journal row **before HTTP 200**.
2. `(group_id,id)` is the stable idempotency key. Retries with changed payload return HTTP 409.
3. A gateway worker CAS-claims work and uses a fenced lease token for commit. Stale completions are ignored.
4. Pi task creation + its job-ID ledger happen in one Pi transaction. Restarting Pi reuses that task ID.
5. Completion and callback outbox insertion happen in one gateway transaction. Callback HTTP is retried independently without rerunning the completed worker.
6. Callback delivery is **at least once**, not exactly once. If delivery succeeded but its acknowledgment is lost, Infinity must deduplicate by `(group_id,id)`.
7. Advisory cancellation prevents accepting a late worker completion but cannot reverse external side effects.
8. Pi Durable and the RAP gateway must have a single owner per SQLite database. Distributed HA requires a separately designed storage/ownership service.

## Pros and cons / adoption decision

See [docs/TRADEOFFS.md](docs/TRADEOFFS.md) for the architecture comparison, critical integration risks, and quantitative acceptance gates. The recommended baseline is Pi Durable alone; Infinity is an opt-in coordinator for reactive workflows until it wins the measured bake-off.

## Known limits / gates before production

- No live Infinity daemon, ACP session, GitHub Actions event subscription or hosted model E2E is exercised in this PoC. Validate that separately against a pinned Infinity release.
- `RAP_CALLBACK_ORIGINS` is an exact-origin allowlist; by default only `http://127.0.0.1:4002`. For trusted remote deployment, require HTTPS, auth/mTLS, fixed callback hostnames, DNS/IP policy and network egress isolation. Do not accept arbitrary user-provided callback URLs.
- `RAP_INVOKE_TOKEN` optionally secures invocation and cancellation. Binding to a non-loopback host **requires** it. Production needs enterprise SSO identity mapping and service authentication, not a shared bearer token alone.
- At most one gateway process and one Pi Durable storage owner; lock/HA orchestration is out of scope.
- The local Chord host does not serve remote clients. Principal policies, links and UI integration remain future work.
- Stop-time cancellation and lease-expiry recovery are best effort; long-running external operations need independent idempotency and reconciliation.
- Callback outbox retains history; production requires retention, quotas, dead-letter policies, audit and an operator replay tool.
- The PoC uses one side-effect-free operation. Never use it to execute destructive actions without explicit approvals.
