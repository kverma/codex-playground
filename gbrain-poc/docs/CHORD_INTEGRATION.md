# Chord KnowledgeService — read-only routing experiment

Status: **prototype**. This is the real `@earendil-works/chord@1.4.0` API, not an imitation. It proves compositional RPC and controlled read routing, not durable agent checkpoints, cross-DC cutover or production ACLs.

## Design

```text
Pi Durable / Code Mode (NOT yet hooked up)
       |
       v
Chord KnowledgeService remote contract (version 1)
       |            Reader (recall + routing state)
       |            Operator (+ parity-checked switchReadBackend)
       v
Chord local Router object (one per knowledge space, shared between principals)
       |-- observable state { readBackend, generation }
       |-- pinned read snapshot on each call
       +-- GBrain CLI/PGLite or Oxigraph RDF/WASM MemoryBackend

Authoritative ingester writes to canonical knowledge; no write cutover here.
```

`KnowledgeService` uses Chord's real `remote`, `hostNamed`, `method`, and TypeBox. A shared local `Router` holds routing in a Chord state cell. A router is shared across reader/operator principals, unlike putting state in each principal's remote object (which would split routing). `knowledgeFacet()` is a reloadable facet; `host.reload` updates implementation code without resetting the routing cell.

## API

- `recall({entity}) -> {facts,backend,generation}`: returns results with the pinned engine/generation selected before any asynchronous retrieval, not whatever the route is when the call finishes. No silent fallbacks.
- `routing`: observable readBackend and generation in a Chord state cell, shared across principal views.
- `switchReadBackend({to,expectedGeneration,probeEntities})` (**operator only**): checks a bounded explicit entity sample for text + provenance parity in both engines, rejects mismatches / degraded retrieval, compares generation before and after async validation and then atomically increments routing generation.
- `implementation()` (**operator only**): exposes a tiny version tag to test code-version pinning during hot reload.
- **No public write RPC**: `MemoryBackend.remember/forget` remain in the existing authoritatively controlled memory-ingest layer, not switched by the demo RPC. Switching read path cannot commit writes to divergent engines.

## Guarantees actually tested

1. Real Chord host + remote typed object; reload retains routing state and changes implementation version.
2. A held Chord root continues on the pre-reload code version, while a new call sees new code.
3. A read already underway uses its originally selected engine even if a selector changes concurrently. Results include the pinned generation.
4. Stale generation and divergent candidate facts reject cutover, keeping previous routing state.
5. A viewer crossing a Chord in-memory RPC link lacks mutation/control methods; operator can switch.
6. Checkpoint-like unrelated JSON state remains unchanged in an isolated test. **This is NOT a real Pi Durable checkpoint/resume test.**
7. A CLI smoke runs Chord with real Oxigraph WASM and, with `--gbrain`, the real isolated GBrain CLI/PGLite engine.

## Explicit limitations / safe rollout

- **Not a complete database migration protocol.** Parity for up to 64 submitted entities says nothing about unsampled entities. It is a smoke gate, not global consistency. Production cutover would need a durable authoritative change log, exact revision/cursor handshake, replay, full or auditable sample parity, dual-read shadowing and rollback conditions.
- Memory backend contents are outside Chord state. Chord `routing` persists through hot code reload **only**, not host process restart. Add durable routing with generation/fencing and recovery before real Pi Durable usage.
- The `oxigraph` JS/WASM backend currently holds its graph in memory. Its exported evidence ledger is not an atomic write-ahead log. GBrain is out of process, and its CLI costs are not comparable with WASM process-local latency.
- Principal arguments supplied by tests are trusted. Do not let an HTTP/WebSocket client choose `owner`: bind enterprise mTLS/SSO to verified Chord principals at the ingress and independently authorize reads at the entity/source level. `world`/`private` are metadata, **not** enforcement. Current tests use synthetic non-sensitive data only.
- Parity checks compare facts and provenance. Conflicting memory models, semantic search scores, entity ACLs or temporal views are not included in this route contract.
- There is no live Pi Durable harness wiring, no actual durable checkpoint restart test, no remote multi-process test (only in-memory link), and no official FrontierMemory agent outcome evaluation.

## Commands

```sh
cd gbrain-poc
npm install --ignore-scripts
npm test
npm run smoke:chord -- --out reports/chord-smoke.json  # real Oxigraph + fixture stand-in for GBrain

# Only on a machine with isolated GBrain installed, initialized, with NO model keys:
export GBRAIN_HOME="$(mktemp -d)"
gbrain init --pglite --no-embedding
npm run smoke:chord -- --gbrain --out reports/live-chord-smoke.json
```

Node >=22.19 required by Chord 1.4.0; GitHub Actions uses Node 24.

## Next milestones

1. **Real Pi Durable ingress** via an authorized tool/MCP adapter calling `KnowledgeService`. Resume a task after process crash with a persisted route generation, explicit pin for the work item, and a stable evidence snapshot.
2. Durable routing control (transactional generation + fencing), authoritative event journal, idempotent replay and cutover freeze; test crashes at each phase of promotion.
3. Full corpus semantic/temporal/ACL bake-off for both engines, with no leakage of held-out incidents.
4. FrontierMemory/agent evaluator against actual end-to-end incident diagnosis, same model/tool/effort budget.

No agent should promote an inferred lesson to an executable skill without independent outcome verification and approval.
