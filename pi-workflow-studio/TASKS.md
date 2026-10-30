# Task backlog and explicit completion gates

Status: phase-1 reference implementation done; production integration not done.

## P0 — durable correctness

- [ ] Replace FileStore with Pi/Chord transactionally consistent documents or
      an authoritative DB with CAS, leases and recovery, including a multi-writer
      test that demonstrates no duplicated ownership.
- [ ] Rework the Pi bridge into per-step durable phases and an authenticated
      approval wakeup; test process interruption on each phase boundary.
- [ ] Implement authorization lease/revocation checks at every external tool
      call; test SSO group removal while paused and while retrying.
- [ ] Add a durable gateway outbox, receipts, external idempotency contract,
      reconciliation of uncertain outcomes, and negative tests for providers
      that do not honor keys.
- [ ] Add event source verification, unique event identity, subscriptions,
      cancellation, temporal waits, backoff, timeout and bounded concurrency.

## P1 — Serval-style authoring product

- [x] Pi conversation authoring adapter with faux-model E2E test.
- [x] Validated JSON workflow IR and reviewable TypeScript export.
- [x] Immutable published versions, approval and permission gates.
- [ ] Connect the approved model proxy (no model credentials in this PoC).
- [ ] Inject KnowledgePort/KG retrieval into the builder's scoped context,
      with provenance, contradictions and memory version pinning.
- [ ] Add preview/simulation, conversational diff and a workflow editor UI.
- [ ] Add SDK contracts for tool schema validation and MCP registry versioning.
- [ ] Support branches, loops, foreach, fan-out/join, compensations and timers
      via new versioned IR features with adversarial tests.
- [ ] Add signed release manifests, rollback/canary, compatibility migration
      and workflow invocation APIs for Slack, web and other clients.

## P2 — Hermit continual learning

- [x] Independent held-out evaluation and safety / regression gate interface.
- [ ] Create privacy-filtered rollout export: task IDs, public input,
      execution trace summary, expected outcome and judge-owned labels.
- [ ] Connect the Hermit 0.1 acting pipeline to a Pi Durable session, including
      applying the adapter context, skills and tool definitions to each rollout.
- [ ] Provide proper task/train/val/test splits and reproducible run.yaml,
      pipeline.py, judge.py and task-info/README.md.
- [ ] Test baseline eval → GEPA/SkillOpt train → held-out eval. Compare also
      Continual Harness in a quarantined workspace with separate gate.
- [ ] Build skill/policy adapter loader with version pins and automatic rollback.
- [ ] Add time, token, money and privacy budgets; audit every proposed edit.
- [ ] Benchmark on real recurring workflows and measure generalization,
      objective quality, error rates and operator intervention, not just
      whether a synthetic toy task can learn a formatting rule.

## Reference contracts

- Pi Durable (experimental API): https://github.com/earendil-works/pi/tree/main/packages/durable
- Hermit (v0.1): https://github.com/Infini-AI-Lab/hermit
- Hermit custom task:
  https://infini-ai-lab.github.io/hermit/docs/tutorials/your-own-task.html

Use a new, isolated module for actual integration with Marvin. Do not treat this
experimental PR as deployment authorization.
