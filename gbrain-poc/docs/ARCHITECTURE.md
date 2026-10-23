# Architecture and decisions

## Boundaries

```text
Pi / Pi Durable [NOT IMPLEMENTED HERE]
   | checkpoint, event receipts, tool calls, Code Mode and RLM external output store
   v
Memory gateway (TS) ------- audit/approval gate ------- self-evolution proposals
   | remember / recall / forget (neutral contract)        | independent review + regression
   v                                                      v
GBrain keyless CLI (PoC)                            version-controlled skills [future]
   | local PGLite and Markdown; no credentials           
   v
synthetic evidence-backed facts
```

Pi Durable remains authoritative for progress, retries, idempotency and pending approvals. GBrain is authoritative only for explicit long-lived facts and graph pages, NEVER for execution state. The adapter cannot update AGENTS.md, tool permissions, policies, prompts or deployed skills.

## Important constraints

1. `origin:tool_output` is rejected; agent must construct an evidence-backed candidate with references, checked by an independent reviewer. The current regex guard is illustrative, not an enterprise-grade DLP solution.
2. `visibility` must be explicit. World-visible facts may be seen by other connected agents. Never use real organizational data in this PoC.
3. Each candidate requires two trusted evidence references, a passing regression and a different reviewer. The demo gate proves only deterministic policy checks; it does not authenticate reviewers.
4. CLI is a transport adapter, not a runtime plugin. Pi's native MCP integration or task hooks must be investigated inside the real durable-agent repository, not fabricated in this unrelated monorepo.
5. GBrain's seven-verb wire protocol is an eventual preferred integration point: `recall, remember, entity, synthesize, forget, context_pack, delta`. CLI `recall` covers the first memory PoC. Use an MCP adapter after Pi harness capability inspection.
6. Future memory contracts should include source IDs, as-of timestamps, validity intervals, contradiction handling, evidence annotations and permission labels.

## Metric interpretation

This benchmark uses one confirmed fact per entity, optional expired original, and missing entities. `passed` means expected exact string present AND withdrawn string absent, or zero facts for an unknown entity. It is not a test of causal diagnosis or language-model answer quality. `p95Ms` measures local process recall latency, not end-user task latency. The in-memory fixture naturally benchmarks faster than GBrain; do not compare these as equivalent architectures.

## Next experiments

- GBrain MCP protocol conformance and scoped access / negative permission tests.
- Ingest 50–100 **consented, redacted, actual** incident/runbook documents with temporal updates and counterfactual questions; compare GBrain, Hindsight and Graphiti via a common evidence schema.
- Memory-before/memory-after end-to-end Pi Durable investigations with replayable, independently scored outcomes and abstention.
- Crash recovery across write and checkpoint, content-origin labels, source-scope isolation, deterministic data export and backup restoration.
- Versioned candidate procedures behind policy and regression gates, with explicit rollback and approval logging.

References: https://github.com/garrytan/gbrain/blob/master/docs/protocol/MEMORY_VERBS_v1.md ; https://github.com/garrytan/gbrain/blob/master/docs/INSTALL.md .
