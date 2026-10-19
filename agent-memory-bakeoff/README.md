# Autonomous agent memory: Oxigraph bake-off PoC

**Status (2026-10-18): REAL ENGINE TESTS PASSED.** This is a graph-engine PoC, not a completed Graphiti/Hindsight memory-learning comparison. No production/private data used.

- [RDF engine parity + RocksDB persistence run](https://github.com/kverma/codex-playground/actions/runs/39175712372): **Oxigraph 0.5.11: 6/6 correct; RDFLib 7.5.0: 6/6 correct; reopen: PASS**.
- [Matched scale run](https://github.com/kverma/codex-playground/actions/runs/39175911278): **both engines 6/6 correctness at each scale (85, 850, 4,250, 8,500 RDF quads)**.
- Download ci-smoke.json / ci-scale.json in GitHub Actions artifacts; these include every expected and actual answer and per-query median latency.

## Human-readable cases

1. Current ownership after an October 11 transfer, ignoring a later unreviewed rumor (NewTeam).
2. Historical ownership on October 1 (OldTeam).
3. Two-hop dependency: Svc -> Topic -> Billing.
4. Tenant-filtered access: the atlas query excludes Secret.
5. Episodes INC1 and INC2 have verified successful retry actions; INC3 failed.
6. INC1 resolution has a retrievable evidence source (source/a7).
7. Close RocksDB, reopen store, and repeat all six answers.

**Measured on the same CI host (85 quads):** RDFLib queries 3.498–11.83 ms median, Oxigraph 0.043–0.178 ms median. Ingest: 4.023 ms RDFLib in memory; 23.53 ms Oxigraph including opening persistent RocksDB. Not equivalent durability.

At 8,500 quads, ingestion: RDFLib 220.087 ms, Oxigraph 101.413 ms. Query results remained correct, but this does not establish enterprise SLOs.

## Run this actual PoC

    python -m pip install 'pyoxigraph==0.5.11' 'rdflib==7.5.0'
    cd agent-memory-bakeoff
    python ci_smoke.py
    python ci_scale.py

Both scripts record JSON under results/. GitHub Actions installs real released engine wheels; it does not emulate Oxigraph.

## Limitations and next bake-offs

- The SPARQL tenant predicate is a query convention, **not an enforced security boundary**. Introduce mandatory authorization at the memory API.
- RDF query correctness is not the same as raw-conversation fact extraction, semantic recall, long-term reflection, or autonomous skill promotion.
- **Graphiti, Hindsight, GraphJin are NOT RUN**: need their real services/graph backends and compatible model endpoints, plus identical unstructured transcripts and retrieval budgets.
- Next benchmark needs 100+ temporal corrections, held-out troubleshooting questions, hallucination/abstention cases, poisoning attempts, skill promotion, durability faults and model/cost controls.
- Keep Pi Durable as the runtime; use an independent governed memory API. Do not assume Graphiti can use Oxigraph as its native backend.

Files here intentionally isolated from Atlas and RevealSwift projects.
