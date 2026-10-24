# Oxigraph versus GBrain: controlled memory bake-off

Status: **synthetic, engineering evaluation**; **not** FrontierMemory official score, Pi Durable agent end-to-end, or production-readiness certification.

## Objective

Decide whether a small homegrown typed, bitemporal knowledge model on top of existing Oxigraph infrastructure is worth more engineering than GBrain. Do **not** equate a passing exact-entity test with semantic reasoning or improved model task success.

## Backends and same contract

Both backends implement `MemoryBackend.remember`, `recall`, `forget` with the existing provenance constraints. The comparator creates fresh memory corpora for each adapter and feeds each the identical 72 synthetic entity-recall scenarios and 18 training Pi sessions + 42 held-out diagnostic-selection probes. The run captures exact input-data fingerprints and validates both backends return matching procedure decisions on every held-out case. No live incident data, providers, or evaluation models are involved.

- `GBrainCliBackend`: actual GBrain CLI/PGLite via subprocess; hosted model keys blocked; serves as out-of-process integrated-memory baseline.
- `OxigraphMemoryBackend`: actual `oxigraph@0.5.11` Rust/WASM RDF store in Node; memory is **in-process/in-memory** (no built-in durable disk store for this JS binding). It uses RDF quads, actual SPARQL SELECT, and named, typed relationships.

This is a **functional parity** experiment, not a fair performance benchmark: GBrain p95 includes a new process per operation; Oxigraph p95 does not. Do not compare absolute p95 latency to choose production architecture.

## Canonical knowledge model (implemented prototype)

- Assertion node: immutable `id`, `entity`, `text`, `provenance`, `origin`, `visibility`, `requestId`, `knownAt`, `validFrom`, optional `validUntil`.
- Withdrawal: append `withdrawnAt` and withdrawal reason. Historical as-of reads are preserved.
- Knowledge-time filtering: a fact must have been recorded by the query's `knowledgeAt` and not withdrawn yet.
- Valid-time filtering: `validFrom <= validAt < validUntil` (end exclusive).
- Correction: withdraw the old assertion and insert a new assertion. Past-knowledge queries can still return what was believed before correction, even when the valid time is older.
- Relation: directed, typed, evidenced edge (`depends-on`, `caused-by`, `mitigated-by`, `verified-by`, `supersedes`). Graph traversal is bounded and does not infer causality automatically.
- Ledger: export append-only JSON events and replay into an ephemeral Oxigraph store; for production store the ledger in a durable authenticated service and validate each event before replay. The PoC's ledger is memory-only.
- Request idempotency: deterministic assertion identity from `requestId + entity`, rejects changed payload replay, instead of silent last-write-wins.

The service layer is responsible for source grants, ACLs, review, and enterprise tenancy. The backend's `visibility` string is **metadata**, not an enforced permissions boundary. Do not feed it proprietary production traces.

## Exact commands

```bash
cd gbrain-poc
npm install --ignore-scripts
npm test
npm run benchmark:compare -- --out reports/graph-comparison.json
# Native GBrain integration requires the separately installed GBrain CLI and isolated GBRAIN_HOME:
npm run benchmark:compare -- --gbrain --out reports/live-backend-comparison.json
```

The second command fails closed when the backend datasets or decisions differ; it reports `graph`, `gb`, and `parity` in the JSON. No silent fallback from GBrain to the fixture is allowed.

## Explicit bake-off stages

1. **Shared synthetic recall / transfer:** 72 entity queries, 42 held-out tasks; identical evidence and expectation hashes, parity on 42 output decisions. This stage can run in GitHub Actions.
2. **Graph-only temporal and relationship correctness:** correction before/after learning, valid-time boundaries, 2-hop traversals, request conflicts, replay and secret guards. These tests verify extra KG features; they are *not* GBrain-versus-graph wins.
3. **Equitable retrieval evaluation (not yet implemented):** feed consented redacted production-like data into *both* retrieval stacks, with identical indexing budgets; test semantic paraphrases, multi-hop context, stale corrections, abstention, ACLs. No exact-entity query hints at evaluation time.
4. **Agent outcome evaluation (not yet implemented):** Pi Durable agent uses each backend with identical model, prompts, tools, token budgets and blinded incidents. Score incident resolution with external verifiers, time/cost and behavioral improvement. Evaluate actual transfer to unseen classes of incidents.
5. **FrontierMemory official harness (not yet implemented):** memory-generation adapter + gated 674-trajectory corpus, fixed model/effort, 15 benchmark tasks, 3 independent runs; preserve provider access policy and required evidence trace. The synthetic `MEMORY.md` export does not constitute an official leaderboard run.

## Decisions to postpone

- Do not choose Oxigraph for performance based on in-process latency versus GBrain CLI process spawning.
- Do not claim graph-only tests prove GBrain lacks analogous capabilities.
- Do not adopt in-memory JS/WASM storage for production without durable ledger/recovery and multi-process replication semantics.
- Do not let inferred relationships become executable procedures without independent review and regression evidence.
- Do not publish mixed-trust data in one freely searchable graph; enforce source authorization before retrieval.

**Provisional outcome:** the custom graph provides an explicit assertion/evidence/temporal model and controlled typed traversal while reusing a real RDF engine. Whether it improves autonomous diagnosis is unmeasured until Stage 4.
