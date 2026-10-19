# Genie, RLM, Harness², Prime Agent: architecture and bake-off

October 19, 2026. Research status: public implementations examined; no full-model head-to-head run yet.

## Different roles

- Hindsight / Graphiti / Oxigraph: durable factual, episodic, temporal and graph memory.
- Uber Genie EAg-RAG: document enrichment; query optimization and source identification; lexical+dense search; deduplication and context reconstruction.
- RLM: recursive, programmatic investigation of large external evidence collections. Tool results remain in external variables/handles; pass selected bounded evidence to language models.
- Prime Agent: Pi-derived open-source agent with persistent IPython RLM, long-lived subagents, durable history, and a Continual Harness able to update prompt notes, memories, skill descriptions and subagent specifications based on recorded trajectory. Present-day implementation is largely Rust with a Python kernel; not drop-in TypeScript/Pi Durable code.
- Harness²: Google Research (not a supported Google product), Python framework with task-level Propose–Probe–Compose of harness variants and harnessing-level persistence of evidence-supported improvements across tasks. Editable elements are prompts, guardrails, memory, tool guidance, subagents, skills, scripts and plugins.

## Recommended architecture

Pi Durable -> Authorized memory API -> Graphiti/Hindsight/Oxigraph + indexes
           -> Genie-style query planner / hybrid retrieval / source reconstruction
           -> read-only RLM investigation across *evidence references*
           -> grounded answer, audit and execution outcome

Offline improvement process: outcomes + redacted trajectories -> proposal of query-router policies,
harness prompts and verified procedures (Prime-style refinement or Harness² optimizer) -> replay
on training/held-out tasks -> adversarial regressions -> explicit human/team promotion -> versioned runtime.

Raw tool responses never automatically enter model context; retrieved evidence is referenced by
opaque immutable IDs and materialized on demand under authorization checks. Retrieved text
can never grant execution permission or rewrite higher-trust security policy.

## Controlled factorial bake-off

A: Existing Graphiti native episode BM25; Hindsight chunks without/with reranker.
B: A + Genie-style decomposition, metadata routing, reciprocal-rank fusion and contextual windows.
C: A + actual RLM recursive retrieval/verification, external evidence handles and bounded output.
D: B + RLM.
E: D + harness refinement from training incidents via Propose–Probe–Compose or Continual Harness,
   with versioned promotion and never-before-seen held-out incident families.

Measure evidence Recall@5, citation precision, temporal truth, false-belief inclusion, abstention,
unseen-incident diagnosis success, skill regression rate, p50/p95 latency, inference tokens/cost,
CPU/RAM, and total tool calls. Compare quality/cost Pareto tradeoffs, not latency alone.

Controls: no answer IDs in held-out natural-language questions; same model and inference setup;
training/validation/test split by time and incident family; comparable budgets; authoritative as-of
timestamp and source trust hierarchy; tenant checks at the memory API; blinded grading. Include
absent-answer and poisoned-memory tests. Evidence retrieval success is not correct synthesis.

Harness² upstream warns its executor has unrestricted network access and Codex substrate disables
sandboxing; public benchmark rubrics may be available. Use dedicated disposable isolated hosts
with egress restrictions, not company access, for experiments. Prime Agent's recoverable worker
and kernel are not a security sandbox either.

## Intended harness contracts

MemoryRouter.search(question, principal, asOf, budget) -> evidence refs, retrieval trace, snapshots
RLM.investigate(question, evidenceRefs, modelBudget, recursionLimit) -> grounded findings, citations, trace
HarnessCandidate(sourceVersion, proposedPatch, supportingOutcomes) -> preflight and regression results
Promote only after policy, safety and holdout quality gates.

## Execution ledger

- Graphiti/Hindsight REAL service evidence-retrieval bake-off: see FRAMEWORK-RESULTS.md and CI artifacts.
- Harness² deterministic offline smoke: PASSED on 2026-10-19 (official Harness² upstream SHA 1cfe254e8d25b15f37048c3730134686abe60335, both parallel and sequential modes, 2/2 demo tasks, 0 failed, output artifacts). CI: https://github.com/kverma/codex-playground/actions/runs/39297164222 . This validates the official local deterministic demo, not model-backed memory/retrieval improvement.
- Prime Agent/RLM and full Harness² evaluation with LLMs: NOT YET RUN. No inferred numbers.

## Sources

- https://www.uber.com/blog/enhanced-agentic-rag/
- https://arxiv.org/abs/2512.24601 and https://github.com/alexzhang13/rlm
- https://arxiv.org/abs/2610.14224 and https://github.com/google-research/harness2
- https://arxiv.org/abs/2608.23552 and https://github.com/PrimeIntellect-ai/prime-agent
- https://www.primeintellect.ai/blog/prime-agent
