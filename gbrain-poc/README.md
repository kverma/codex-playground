# GBrain × Pi Durable: evidence-gated memory PoC

**Status:** prototype / synthetic-data evaluation, **not a production integration**.

This subproject does not change RevealSwift or the agent's Pi Durable implementation. It tests a small `MemoryBackend` boundary backed by GBrain's documented CLI, plus an in-memory fixture, while keeping durable execution state, original tool outputs, secrets, and approval state outside the memory engine.

## Run locally (Node >=22.16)

```sh
cd gbrain-poc
npm test
npm run benchmark -- --out reports/fixture.json
npm run benchmark -- --gbrain --out reports/gbrain.json   # requires isolated, initialized GBrain
npm run smoke:gbrain                             # requires isolated, initialized GBrain
```

No npm install is needed for the core PoC: Node built-in TypeScript stripping is used. Real GBrain needs Bun; **install only from `github:garrytan/gbrain#latest-stable`**, not the unrelated `gbrain` npm package. Setup for an isolated, synthetic test brain:

```sh
bun install -g github:garrytan/gbrain#latest-stable
export GBRAIN_HOME="$(mktemp -d)"
gbrain init --pglite --no-embedding
node --experimental-strip-types src/cli.ts smoke-gbrain
```

Do not run against an existing shared brain: all benchmark writes create synthetic `incidents/poc-*` entities and history remains after forget. Do not configure external embedding/chat API keys. CLI subprocesses use an allowlist environment without model API keys. `gbrain protocol conformance` should be run separately for server protocol verification; the smoke probes CLI integration only.

## What the PoC proves

- Backend-neutral `remember / recall / forget` interface with provenance and explicit visibility.
- A **72-case synthetic** trace: 48 known, 12 corrected/withdrawn, 12 unknown/abstain; p50/p95 recall latency and pass/fail per case.
- An evaluation report that includes expected and observed facts, plus human-readable test intent.
- No writes of raw tool output; basic secret guard; independent reviewer plus supporting evidence and regression required for candidate promotion.
- Real GBrain smoke and full 72-case GBrain run are opt-in. The GitHub Actions fixture gate is always on; GBrain end-to-end smoke and the 72-case real-CLI benchmark run in a separate live job on PR/push (optional for manual dispatch).

## What it does NOT prove

- Answer accuracy with a decision/LLM model; semantic or graph retrieval quality (entity-scoped exact recall only).
- Native Pi Durable checkpoint hooks, Pi MCP tool registration, or autonomous skills evolution.
- Production tenancy isolation, temporal graph semantics, enterprise auth, robust secret-detection, or crash recovery.
- Performance on real incidents: **this benchmark corpus is synthetic**.

See `docs/ARCHITECTURE.md`, `docs/HANDOFF.md`, and project-specific `AGENTS.md` for the next integration steps. Never interpret fixture accuracy as GBrain accuracy.
