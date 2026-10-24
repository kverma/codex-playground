# Architecture decision: Should Pi use Infinity?

**Status:** Provisional, pending real Infinity + Pi Durable/Chord integration testing (2026-10-23).

## Decision

Do not deploy three overlapping agent runtimes by default. Retain Pi Durable as owner for existing Pi conversations and tasks. Integrate Infinity at its RAP boundary as an **optional independent coordinator** for workflows which demonstrably need reactive subscriptions, per-thread mailboxes, or agent hibernation. Use Chord for typed control/API and live status projections, not for durable event admission. Use the `infinity acp` boundary when delegating *coding-agent sessions* from Pi to Infinity Code, rather than sending them through RAP.

## Alternatives considered

| Approach | Pros | Cons | Recommendation |
|---|---|---|---|
| A. Pi Durable + Chord only | One language, one agent owner, simple ops; already durable task graphs, sleep, subagents | Need host scheduling/event gateway/subscriptions for reactive wake-up | **Baseline**; benchmark against Infinity |
| B. Pi Durable + Infinity RAP (Chord optional) | Reuse Infinity's reactive runtime/hibernation/isolated event threads; boundary stays standard HTTP | Two stores, at-least-once callbacks, duplicate scheduler semantics; new recovery runbook | **Pilot** for event-heavy workflows |
| C. Infinity + Chord + Pi Durable for everything | Comprehensive toolbox, typed UI state + Rust runtime + durable TS workers | Highest operational cost and risk of split-brain task ownership | **Avoid as default** |
| D. Infinity Code via ACP only | Least invasive way to reuse Infinity's coding agents/sandbox sessions | Not Infinity Runtime event orchestration | **Quick win** if ACP sessions are the primary need |
| E. Rewrite Infinity mechanisms in TypeScript | Unified TypeScript service with custom ownership | Recreates large runtime surface; permanent maintenance | Not recommended |
| F. Replace Pi Durable with Infinity | One durable orchestrator once migrated | Loses existing Pi task/data APIs; major migration/test cost | Not now |

## Advantages worth validating

- Reactive work wakes on externally delivered events rather than keeping a reasoning loop active.
- Independent Infinity threads isolate high-volume GitHub/Slack events from long parent transcripts.
- RAP server contract supports language-neutral workers and independently deployable specialist tools.
- Pi Durable already supplies atomic task checkpoints, durable submissions, subagents and replay-aware tools; no need to port them.
- Chord's reactive state enables live UI status and hot-swappable application objects without putting UI updates into the business journal.

## Integration risks (ranked)

1. **Cross-system atomicity (critical).** Neither Infinity's commit nor Pi's commit is atomic with the RAP gateway. Admit to an outbox before ACK; correlate results; never claim exactly-once callbacks or effects. Pi task creation must be idempotent by job ID.
2. **Two task owners (critical).** Infinity owns its threads and workflow-level scheduling; Pi Durable owns delegated task internals. Never replay both loops to independently execute one external side effect. Define cancellation and deadline policy at the boundary.
3. **Storage/recovery coupling (high).** At least three stores may coexist (Infinity, RAP outbox, Pi Durable), each with independent backup/restore and upgrade semantics; Chord is not the fourth authoritative store.
4. **Security (high).** Treat callback URL as SSRF input; require service authentication, fixed endpoints, mTLS and egress policy for internal production. Chord principals do not replace transport identity or permissions. Never forward long-lived corporate credentials to delegated workers.
5. **Version compatibility (high).** Pi Durable and Chord 1.4 describe themselves as experimental; pin dependencies and test each upgrade. RAP may evolve independently.
6. **Distributed scaling (high).** Infinity step mode requires per-thread serial delivery. Pi Durable storage has one process owner. Neither requirement disappears because the gateway has a lease table.
7. **Observability (medium).** Trace IDs, job IDs and logs must cross Rust/TypeScript/HTTP. Late or orphaned callbacks need diagnostic visibility and dead-letter treatment.
8. **Performance/cost (medium).** Every boundary adds JSON serialization, round trips, resources and on-call surface. Benchmark idle footprint, p95 resume latency, cost per job and loss/retry rates.

## Quantitative acceptance gates before adopting Infinity in production

Compare A vs B on the same event workload, same model/provider and same storage durability level:

- Correctness: 0 lost acknowledged jobs in injected process crashes (including before/after each commit), with all unfinished jobs terminal or operator-visible.
- Dedupe: no extra Pi task for a duplicate `(group_id,id)` and no unintended duplicate external effects when external idempotency is supported.
- Recovery: a callback delivered before its HTTP acknowledgment may be redelivered; one user-visible outcome must result. Test stale-owner fencing.
- Efficiency: report p50/p95/p99 wake-to-first-token latency, idle RSS, throughput and CPU rather than assert unmeasured improvements.
- Operational complexity: document number of processes/stores, upgrades, data restoration steps, and required on-call runbooks.
- Security: corporate identity propagation and principal authorization through Chord and RAP must be separately tested.
- Compatibility: pin Infinity version/revision; test Infinity daemon against real RAP worker end-to-end.

## PoC boundaries

The dependency-free local suite tests HTTP/SQLite gateway behavior with a fake worker. TypeScript integration tests require npm and use real Pi Durable + Chord. Real Infinity daemon behavior, end-to-end hibernation and cross-process cancellation remain unverified until exercised against Infinity itself. **These are gates, not established properties of this PoC.**

## References

- https://infinity.hydro.run/docs/rap/using-rap/building-a-rap-tool/
- https://infinity.hydro.run/docs/infinity-runtime/agent-systems/step-mode/
- https://github.com/earendil-works/pi/tree/main/packages/durable
- https://github.com/earendil-works/pi/tree/main/packages/chord
