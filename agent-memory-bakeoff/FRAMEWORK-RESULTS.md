# Graphiti vs Hindsight: reproducible agent-memory PoC report

**Run date:** 2026-10-19. **Status:** real no-LLM services tested; model-based learning evaluation not yet executed. **Source:** [Draft PR #2](https://github.com/kverma/codex-playground/pull/2).

## TL;DR

- The 9-episode baseline returned **15/15 expected evidence top-5 for both** Graphiti's native episode BM25 and Hindsight's local chunk recall. These are evidence lookup tests, NOT answer accuracy.
- After adding 80 adversarial similar-looking episodes, Graphiti BM25 retained **15/15 top-5**, Hindsight chunks without reranking fell to **4/15**, and enabling Hindsight's local MiniLM cross-encoder increased Hindsight to **14/15** (8/8 held-out identifier-free cases).
- Cross-encoder retrieval is materially more expensive (~716 ms median vs ~54 ms without reranker). The Graphiti BM25 result (~9.9 ms) measures a different retrieval mechanism. This is **not** a fair end-to-end full-product winner determination.
- Raw episodic ingestion uses no extraction/reflection. Provenance and isolation are only superficially tested. Neither autonomous procedural learning nor trustworthy answer synthesis has been established.

## Real systems

- Graphiti **0.31.1** with Neo4j **5.26**, API `EpisodicNode.save` + `Graphiti.search_` configured with native episode BM25 and RRF; dummy embeddings are injected but never used for this particular retrieval. No LLM called.
- Hindsight server **1.0.0**, SDK `hindsight-client==1.0.0`, tested pinned image digest `sha256:a88678c038b0e4340822b02a5efc566c56d8176b5bffa731bc433fd37f978e4c`, `chunks` ingestion; local `BAAI/bge-small-en-v1.5` embeddings, initially reranking off, then `HINDSIGHT_API_RERANKER_PROVIDER=local` and `cross-encoder/ms-marco-MiniLM-L-6-v2`. No LLM extraction or reflection.
- Previous Graphiti+FalkorDB attempt initially surfaced disconnected database/index queries and 0 hits while CI showed green. This was fixed by using Neo4j and requiring nonempty retrieval; do not count the faulty run.

## Stress test results: 89 episodes, 15 questions

| Metric | Graphiti + Neo4j episode BM25 | Hindsight chunks, no rerank | Hindsight chunks, local MiniLM rerank |
|---|---:|---:|---:|
| Correct evidence in first 5 | **15/15** | **4/15** | **14/15** |
| Eight identifier-free questions | **8/8** | **2/8** | **8/8** |
| Correct evidence at rank 1 | **12/15** | **1/15** | **12/15** |
| Total ingestion | 2708.901 ms | 6222.672 ms | 7949.452 ms |
| Median query latency | 9.929 ms | 54.18 ms | 716.115 ms |

### Question-by-question expected evidence and observed rank

Rank 1 = first result; ranks 2-5 count hit@5; MISS means absent from first five.

| Test objective | Expected ID | Graphiti rank | Hindsight no-rerank rank | Hindsight reranked rank |
|---|---|---:|---:|---:|
| causal | INC-101 | 1 | MISS | MISS |
| successful-mitigation | INC-102 | 1 | MISS | 1 |
| current-runbook | OPS-002 | 1 | MISS | 1 |
| historical-owner | OWN-001 | 1 | MISS | 2 |
| new-owner | OWN-002 | 1 | 1 | 1 |
| failed-procedure | INC-101 | 1 | 3 | 1 |
| recurring-pattern | INC-103 | 1 | MISS | 1 |
| cause-paraphrase | INC-101 | 1 | 5 | 1 |
| remedy-paraphrase | INC-102 | 1 | MISS | 1 |
| playbook-paraphrase | OPS-002 | 2 | 3 | 1 |
| historical-owner-no-id | OWN-001 | 3 | MISS | 2 |
| current-owner-no-id | OWN-002 | 1 | MISS | 1 |
| failed-action-no-id | INC-101 | 1 | MISS | 1 |
| last-incident-no-id | INC-103 | 1 | MISS | 1 |
| untrusted-claim | NOTE-001 | 2 | MISS | 1 |

### Representative examples

- **Causal incident** expected `INC-101`: Graphiti rank 1; Hindsight no-rerank MISS; Hindsight reranked MISS. This remained difficult because many distractors said Kafka HTTP 429 throttling.
- **Current owner correction** expected `OWN-002`: Graphiti 1; Hindsight no-rerank 1; reranked 1.
- **Untrusted ownership rumor** expected `NOTE-001` as **evidence of the rumor**, not accepted as true. Its successful retrieval is not evidence that the memory system rejected the rumor; answer-level contradiction tests remain pending.

## Validation and fairness boundaries

1. This is a **tiny deterministic synthetic dataset**, not production-like distributions. Descriptive query strings overlap corpus text and lexical methods enjoy an advantage.
2. The exact document ID is used to grade **evidence retrieval**, not factual extraction or synthesis. The corpus contains documents stating mutually inconsistent claims; retrieval success does not show correct temporal resolution, trust evaluation, or skill learning.
3. The isolation probe excludes a separate bank/group while searching in the authorized one, but authorization is enforced by the test query, **not shown as a security boundary** against arbitrary clients.
4. Hindsight and Graphiti ran on **separate GitHub-hosted runners** and with **different backend and retrieval architectures**; timings are observations, not apples-to-apples throughput benchmarks. Reranker inference was local CPU and increased latency markedly.
5. For Oxigraph, previous RDF/SPARQL 6/6 correctness tests apply to an **already structured 85–8,500 quad corpus**; its microsecond queries cannot be compared to the natural-language retrieval timing above.

## Artifacts and reproduction

- [CI: matched 89-episode comparison without reranker](https://github.com/kverma/codex-playground/actions/runs/39276185199) — JSON artifacts, expected and actual evidence IDs per query.
- [CI: Hindsight local reranker comparison](https://github.com/kverma/codex-playground/actions/runs/39276839702) — JSON artifacts.
- `agent-memory-bakeoff/memory_framework_bakeoff.py`: fixture generation, deterministic 80 distractors, native calls, scoring.
- `.github/workflows/agent-memory-frameworks.yml`: actual Docker-backed CI runs.
- `agent-memory-bakeoff/memory_llm_bakeoff.py` and `.github/workflows/agent-memory-llm.yml`: **opt-in and not executed**. Requires real approved model credentials/endpoints.

## Next gates before choosing an engine

1. Run equivalent **LLM-enabled entity/fact extraction and temporal update reconciliation**. Pin the same chat and embedding models; record cost and tokens.
2. Test **answer correctness**, not just evidence ranking: current vs historical ownership, explicit unverified rumor, failures, abstention when evidence is absent.
3. Test **reflective/procedural learning** from sequential incidents: does the memory system surface the correct lesson without contaminating next decisions?
4. Run **concurrency, crash recovery, reranker memory/CPU usage, bank isolation enforcement**, high-cardinality scaling, and tail latency.
5. Add independent lexical BM25 and a standardized hybrid reranker as controls, with all models/rerankers identical where possible.

**Decision:** Both Graphiti and Hindsight are viable PoC candidates. Graphiti gives a strong fast lexical episode baseline; Hindsight's reranker materially improves robustness among near-duplicate memories. No complete autonomous-memory winner can be established yet.
