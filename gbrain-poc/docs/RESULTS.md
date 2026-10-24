# GBrain × Pi Durable PoC — verification results

**Date:** 2026-10-23  
**Repository:** `kverma/codex-playground`  
**Source branch:** `feat/gbrain-pi-durable-poc`  
**Verified revision:** `307c0f8d706866da3374d7c792edca6cef919382`  
**GitHub Actions run:** https://github.com/kverma/codex-playground/actions/runs/40040054113

## Objectives and outcomes

| Objective | Test method | Observed result | Outcome |
| --- | --- | --- | --- |
| Validate type-safe memory facade | Native Node TypeScript tests | 5/5 tests passed | PASS |
| Verify fixture behavior | In-memory 72-question incident set | 72/72 passed | PASS |
| Verify actual GBrain API round trip | GBrain CLI with fresh, keyless PGLite brain | `remember → recall → forget → recall` succeeded | PASS |
| Exact entity-scoped GBrain recall | 48 synthetic known incidents | 48/48 expected facts returned | PASS |
| Correction / withdrawal | 12 obsolete facts replaced by confirmed facts | 12/12 old facts absent and new facts present | PASS |
| Abstention when knowledge absent | 12 never-populated entities | 12/12 returned zero facts | PASS |
| Measure read latency | CLI subprocess per GBrain recall | p50 **559.201 ms**, p95 **575.495 ms** | MEASURED, not gated |

The 72-case result is **actual GBrain CLI/PGLite behavior**, not merely the local in-memory fixture. These results test exact entity-scoped memory, **not** semantic/graph ranking or an LLM's causal incident diagnosis. The exercise writes synthetic records only and supplies no model API credentials.

## Representative test traces

### Q049 — incorrect earlier belief is withdrawn

**Objective:** After a hypothesis is disproven and the validated remediation is recorded, normal recall must not present the obsolete hypothesis as current.

**Setup / write sequence:**

1. `remember(entity="incidents/poc-049", fact="INC-049 obsolete initial assumption", provenance="synthetic:Q049:obsolete")`
2. `remember(entity="incidents/poc-049", fact="INC-049 cassandra wrong-term validated action verify-read-quorum", provenance="synthetic:Q049:validated")`
3. `forget(id=<first receipt ID>, reason="superseded by confirmed evidence")`
4. `recall(entity="incidents/poc-049")`

**Expected:** only the validated Cassandra fact, no obsolete assumption.

**Actual:** `["INC-049 cassandra wrong-term validated action verify-read-quorum"]`.

**Result:** PASS; elapsed **559.362 ms**.

### Q061 — unknown incident must not create an answer

**Objective:** Recalling an entity absent from memory must return no asserted fact.

**Sequence:** `recall(entity="incidents/poc-061")` — no preceding memory writes.

**Expected:** `[]`.

**Actual:** `[]`.

**Result:** PASS; elapsed **561.978 ms**.

### Q001 — persisted known fact

**Objective:** Memory remembers attributed fact across separate CLI processes.

**Sequence:** `remember` confirmed fact for `incidents/poc-001`, followed by `recall`.

**Expected:** `INC-001 cassandra stale-read validated action verify-read-quorum`.

**Actual:** same exact text; result PASS, **575.030 ms**.

## Reproduction

```bash
cd gbrain-poc
npm test
npm run benchmark -- --out reports/fixture.json

# Separate disposable brain, Bun + actual GBrain installed:
export GBRAIN_HOME="$(mktemp -d)"
gbrain init --pglite --no-embedding
npm run smoke:gbrain
npm run benchmark -- --gbrain --out reports/gbrain.json
```

The run artifact `gbrain-live-smoke-report` contains **live-smoke.json** and **live-benchmark.json**, including individual 72-case expected/observed traces. See the GitHub Actions run linked above.

## Interpretation and remaining validation

This validates the integration's CLI contract and keyless database-backed functionality. It does **not** validate native Pi Durable execution, MCP tool wiring, semantic search, graph temporal correctness, source-scoped enterprise auth, data-leak prevention, hallucination rates or self-improving decision quality. The simple regex secret filter and independent-review string check are illustrative only.

Next evidence gate: use an approved redacted incident corpus and evaluate GBrain against Hindsight/Graphiti with identical gold labels; measure retrieval precision, stale fact admission, abstention, correct procedures, false escalations, and end-to-end Pi Durable incident success. Pin the GBrain upstream commit rather than a mutable tag before deployment.