# Agent handoff and verification

## Objective

Demonstrate a memory boundary around Pi Durable without conflating semantic knowledge with execution persistence. Standalone PoC lives under `gbrain-poc/` because this GitHub repository otherwise hosts RevealSwift, not a checked-out Pi Durable harness.

## Commands

- `npm test` — deterministic 5-test native Node suite (no third-party runtime packages).
- `npm run benchmark -- --out reports/fixture.json` — emit versioned JSON receipt; fail on a discrepancy.
- `npm run benchmark -- --gbrain --out reports/gbrain.json` — opt-in real GBrain CLI 72-case evaluation, writes synthetic facts.
- `npm run smoke:gbrain` — real remember→recall→forget→recall round-trip.

## CI gates

`gbrain-poc.yml` runs fixture tests/benchmark on PR and push. The live job installs Bun + GBrain, executes keyless, isolated smoke and the 72-case benchmark on push/PR; manual `workflow_dispatch` can skip it with `run_gbrain=false`. It must report both jobs distinctly: fixture is not evidence that GBrain worked. Workflow output is a fixture artifact; manually triggered live smoke produces a separate receipt.

## Integration checklist

- Inspect actual Pi Durable runtime and package versions; do not assume native Pi hooks or checkpoint API.
- Implement a Pi tool adapter calling `MemoryBackend` with strict post-authorization source scoping.
- Add durable request IDs, fencing, operation receipts, source permissions and redacted evidence envelopes.
- Add real-corpus benchmark and independent gold labeling.
- Never let dream-generated summaries auto-promote executable skills or permissions.
- Record exact upstream GBrain version/commit and tool schemas when real smoke is run.

## Decision pending

Promote GBrain only if real-corpus retrieval, stale-memory, source isolation, and agent task success beat the incumbent memory backend by a meaningful and reproducible margin; otherwise retain it as a reference design.
