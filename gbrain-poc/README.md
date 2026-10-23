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

See `docs/RESULTS.md` (measured GitHub Actions outcomes), `docs/ARCHITECTURE.md`, `docs/HANDOFF.md`, and project-specific `AGENTS.md` for the next integration steps. Never interpret fixture accuracy as GBrain accuracy.

## FrontierMemory-style continual-learning test (new)

FrontierMemory 1.0 evaluates whether a memory-generation harness improves outcomes on *held-out tasks*, not simple factual recall. This repository adds a separate, **synthetic deterministic transfer test**, not an official FrontierMemory run or proof that an LLM has learned.

```sh
cd gbrain-poc
npm install --ignore-scripts  # installs pinned @letta-ai/trajectory 0.4.3
npm test                     # includes Letta's real Pi JSONL normalizer
npm run benchmark:transfer   # no-memory vs learned procedures vs oracle; emits report & MEMORY.md
npm run benchmark:transfer -- --gbrain  # isolated initialized GBrain only
```

`@letta-ai/trajectory` consumes native Pi SessionManager JSONL, with verifier receipts retained as trusted separately labeled metadata; fake assistant statements of success cannot constitute evidence. The fixtures contain **18 training episodes** (12 verified PASS and 6 FAIL), and **42 held-out** diagnostic-selection probes (30 unseen services using learned procedures, 6 unknown, 6 ambiguous). Train and test resource IDs are disjoint, deterministic SHA-256 digests are included, and the task-solving routine cannot access the `expected` answer. A no-memory control contains two generic fallback rules. Accepted synthetic procedures also export as `reports/portable-memory/MEMORY.md`, adhering to FrontierMemory's plain-text memory format and 8 MiB cap. This export is only a format smoke, **not a FrontierMemory leaderboard submission**.

Promotion here is a simulated evidence gate, not real independent human approval. These are deterministic rules operating on **already classified synthetic signals**; they do not evaluate signal recognition from raw logs, reasoning-model effectiveness, generalization to new systems, or actual Pi Durable execution. A proper next test would pin an approved model/harness, run its real task loop on withheld incidents, and report absolute gains plus cost and failure rates. Official FrontierMemory also requires the gated 674-trajectory corpus, Harbor, model inference, a 15-task suite and three independently verified runs for leaderboard submission; those are deliberately **not claimed** here.

References: https://github.com/letta-ai/frontiermemory and https://github.com/letta-ai/trajectory.

## Oxigraph KG versus GBrain: identical-evidence bake-off

An additional `OxigraphMemoryBackend` uses the real **oxigraph@0.5.11 Rust/WASM RDF engine**, with typed relationship edges, independent valid/knowledge times, correction tombstones, and in-memory ledger export/replay. This is an experimental **in-memory** graph, not enterprise durable storage.

```bash
npm install --ignore-scripts
npm test
npm run benchmark:compare -- --out reports/graph-comparison.json
# With an isolated initialized GBrain CLI and no provider keys:
npm run benchmark:compare -- --gbrain --out reports/live-backend-comparison.json
```

Both backends receive identical 72 entity queries and identical 18 training / 42 held-out synthetic decision cases. The real paired runner enforces identical dataset fingerprints and per-task decisions. Do not compare absolute CLI-vs-WASM latencies or interpret 42/42 as model reasoning. See [docs/GRAPH_BAKEOFF.md](docs/GRAPH_BAKEOFF.md) for the evidence model, temporal tests, implementation limitations, and next-stage criteria.
