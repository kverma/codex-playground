# Architecture and handoff

## Why two loops

**Creation**: user → conversation (Pi + Code Mode + catalog) → typed Workflow →
validation → TypeScript export for inspection → human approval → immutable
catalog version → deterministic execution.

**Improvement**: execution receipts + user edits + failures → candidate adapter
or workflow → independent held-out judge → regression and authorization gate →
human promotion. A model may propose edits, but may not self-publish.

Hermit 0.1 (Infini-AI-Lab/hermit) operates on harness adapters (policy,
roles/sub-agents, skills, memory, code). Train those separately from workflow
programs; import only checked adapter versions. Prefer GEPA/SkillOpt validation
and add an independent deployment gate even for Continual Harness, which can
update in place without one.

## Ownership

| Element | Owner |
|---|---|
| Conversation, models, tools, durable task scheduling | Pi Durable |
| Discovery and ad-hoc tool composition | Pi Code Mode + MCP |
| Workflow JSON schema, compiler validation, pinned release | Workflow Studio |
| Run status, tool receipts, approval checkpoint | Workflow Engine (PoC store) |
| Actual user identity, credentials, authorizations, event subscriptions | Host/platform |
| Experience, provenance, skills | Existing Marvin KG / knowledge vault |
| Candidate reflection, experiments | Hermit (optional) |
| Promotion | independent evaluations + human approval |

## Correctness and failure modes

1. A published version is immutable and identified by ID, version, and SHA-256.
2. A run pins its publication digest and never migrates versions mid-run.
3. Each tool call is checkpointed before and after invoking its gateway; retry
   uses the SAME idempotency key. A crash between the effect and the receipt
   may cause the gateway to be called again. Gateway deduplication is mandatory.
4. Approval is its own persisted state. Resume cannot skip a missing approval.
5. Read-only operations and side effects both require explicit capabilities.
6. Unknown tools, bad references, and capability mismatches fail validation.
7. Workflow-generated JavaScript is never evaluated by Node or QuickJS.
8. Candidate quality alone cannot authorize a new workflow or broaden
   permissions. Holdout cases are independent of authoring examples.
9. Each external event needs a stable event ID mapped to runId for deduplication.
10. Never execute the refiner/learner's output without validation and review.

## Production replacement plan

1. Replace FileStore with strongly consistent Chord/Pi documents or another
   transactional store supporting CAS, leasing and concurrent workers.
2. Extend native \`defineTask\` phases per workflow step, storing checkpoints
   under Pi Durable's scheduler; install versioned task definitions and migrations.
3. Add authenticated trigger intake, authorization recheck, human approval
   service, secure secret references, durable outbox and tool receipts.
4. Add event subscriptions, delays, fan-out/join, compensation, bounded
   retries/backoff, cancellation, recovery / Jepsen-like fault-injection.
5. Connect AuthoringModel to the existing enterprise model gateway, protected
   tool catalog, KnowledgePort and conversational refine UI.
6. Export scrubbed traces, tests and evaluations for Hermit's Python trainer;
   compare GEPA/SkillOpt/Continual-Harness adapters with a separate promotion
   validator, then load a chosen adapter per conversation/version.
7. Add shadow rollout, signed release attestations, rollback, cross-DC
   authority, and regression suites covering changing tool contracts.

## References

- https://github.com/earendil-works/pi/tree/main/packages/durable
- https://github.com/Infini-AI-Lab/hermit
- https://infini-ai-lab.github.io/hermit/docs/
- https://www.getserval.com/
