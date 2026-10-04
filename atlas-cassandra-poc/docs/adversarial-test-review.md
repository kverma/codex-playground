# Atlas Cassandra POC: adversarial test review

## Decision

Executable commit `7ba6fd7d27d54218a8747176872d624d1f1bb17b` passed all four jobs in
[run 37243518121](https://github.com/kverma/codex-playground/actions/runs/37243518121).
The grader logs confirm 71 distinct POC cases plus 16 upstream tests passed,
with no failed or skipped cases. This qualifies bounded Cassandra adapter fixtures;
Atlas provider API, compile, Kafka and caching-domain E2E flows remain unvalidated.

This review inspected test code, fault scripts, adapters and stored CI evidence
using safety, availability, Cassandra, chaos, QA and provider-workflow lenses.
It is one code/evidence review, not a claim of independent human or agent sign-off.
Several assertions were too weak to establish their advertised conditions.
The changes below strengthen the harness before broadening product scope.

## Progress snapshot — 2026-10-04

| Area | Progress | Remaining boundary |
|---|---|---|
| Build and hosted validation | Cassandra4.0.5, JDK25, Gradle9.1.0 and Make wrappers; all four hosted jobs green. | No personal-machine dependency; this remains a bounded POC. |
| Independent commercial histories | 400 model and 14 Cassandra histories; overlap/progress checks and six rejected storage/transition mutants. | Shared hash/codec assumptions and unobserved server phases remain. |
| Draft identity and retention | Independent drafts receive distinct tickets without refreshing read sets; exact retries and expiry/floor closure covered. R09/R10 addressed for the candidate. | Full SDK persistence, key rotation, skew and restore behavior remain open. |
| Independent retention histories | 200 model histories, including 100 with lost replies; six Cassandra histories; seven rejected/replayed storage mutants. | Trusted initial state and prepared inputs; 24-call/100,000-state bound. |
| Fault and recovery evidence | Verified isolation, kill witnesses, membership recovery and full healed views matched to the checked final state. R15 strengthened. | Earlier ambiguous-read causes and recovery latency are not established. |
| Maintainer harness | Exact 16 selected upstream tests passed; CassandraUnit compatibility assessment completed. | Upstream smoke does not validate Atlas batches or disk-sync durability. |

The verified total is **71 distinct POC cases plus 16 upstream tests**. The 37
shallow cases are included in the 64-case model/single-node/fault job; seven
three-node cases bring the distinct POC total to 71. Generated histories are
iterations inside these cases, not additional JUnit cases.

Latest results were verified from completed CI logs and strict grader conditions.
Latest artifact downloads stalled; all four archives are published on the linked
run, but a separate inspection of every latest archive is outstanding. Reattempt
that read-only evidence audit before the next protocol change; it does not require
rerunning a green suite.

## Findings and disposition

| ID | Severity | Adversarial finding | Disposition and evidence |
|---|---|---|---|
| R01 | High | All writes could return INDETERMINATE and the checker could accept a no-effect serial history. Safety alone does not establish majority progress. | Fixed in `HistoryWorkload`: bounded exact recovery must terminate, final read must succeed, at least one receipt must exist. An always-indeterminate adapter has a LINEARIZABLE history but must fail the workload grader. |
| R02 | High | A barrier before recording calls did not guarantee overlapping recorded invocations; a short model call could finish before another began. | Fixed: record four invocations before the dispatch gate opens, then assert a common overlap interval. This creates concurrent client operations without claiming a chosen internal Paxos interleaving. |
| R03 | High | Only one scenario checked aggregate OUTPUT counters. History/retention scenarios could rely on command success without packet evidence. | Fixed: preflight reachable peer links, verify blocked TCP probes in both directions, exactly one positive rule per peer/direction, preserve host client access, and verify healing. No-op and missing-INPUT injectors must fail. Counters include probe traffic. |
| R04 | High | The independent oracle rejected immediate token reuse but could accept nonconsecutive ABA generation/group-version reuse. | Fixed: check all previously accepted receipt states in the recorded history. Two new broken stores recycle the initial generation or Royalty token and must be rejected/replayed. This does not discover token history before the recorded initial snapshot. |
| R05 | High | A contact point/local-DC configuration was being treated as evidence of the request's coordinator. Tests could silently weaken consistency configuration. | Fixed: authoritative statements validate SERIAL reads, QUORUM + SERIAL mutations and disabled idempotent replay; successful responses verify the actual driver-reported coordinator DC. Policy observations are saved. Negative controls reject weakened CL/replay settings. Internal replica/phase traffic and failed-request routing remain unobserved. |
| R06 | Medium | A successful Docker kill command did not independently prove the targeted process stopped by SIGKILL. | Fixed: require running target before injection, then stopped state, exit 137, no OOM and PID 0. Save witness before restart. This does not prove commit-log fsync, power-loss durability or a named Paxos phase. |
| R07 | High | A new floor plus REQUEST_TOO_OLD could pass even if old receipt rows were never deleted. | Fixed: retention partition test requires the exact surviving younger ticket key set on majority and healed reads. Logical pruning is checked; tombstone reclamation/disk bounds are not. |
| R08 | High | Scheduling a nodetool process and writes on different threads did not prove a server repair phase overlapped a write. | Claim narrowed. Final full repair completion and LOCAL_ONE final-token convergence remain checked; server-phase overlap is OPEN. Add server-side phase witnessing/pause points or a measured sustained workload before qualifying repair under traffic. |
| R09 | High | Retention admission/recovery tests share `Retention.Base`; there is no independent retention history oracle or model of split floor/delete effects. | Implemented for the bounded controlled-clock profile: `RetentionChecker` independently specifies signed-lease authentication, allocation, retained receipts, acceptance and atomic contiguous pruning, without `Retention.Base` transitions. Two hundred model histories and six Cassandra histories pass; seven actual broken-storage hooks are rejected and saved traces replayed. Black-hole transport fails progress despite a safe history. Initial views and signed prepared inputs are trusted; arbitrary skew, unobserved ambiguous acceptance and histories beyond the checker bounds remain unqualified. |
| R10 | High | Two independent retention drafts read the same next allocator slot and can collide as KEY_REUSE before commercial group validation. | Implemented candidate `prepare`/`issueDraft`: signed nonce/anchor/deadline identifies a draft; server CAS assigns a unique sequence/operation while preserving the original read set and request hash. Shared model/Cassandra contracts require independent edits to accept concurrently, exact allocation replay, payload-binding checks and closure after pruning/backward clock. Legacy `edit`/`issue(Request)` remains sequential-only. Full SDK persistence, signing-key lifecycle and production integration remain OPEN. |
| R11 | High | Same-clock tests and session restarts do not validate skew, restored allocator floors, audit archives, GC grace or resurrected rows. | OPEN. Add skew/jump and stale-snapshot recovery experiments, archive durability and repair/GC qualification. Audit retention is separate from retry retention. |
| R12 | High | RF1 per logical DC on one host is being asked to stand in for RF3/DC and independent-host failures. | OPEN. Nine-node scaffold has no execution evidence. Neither hosted runner tests nor Docker SIGKILL qualify WAN SLOs, disks/power failures or an inter-DC link fault on independent hosts. |
| R13 | Medium | The oracle shares request hashing and the snapshot codec with the implementation. A shared serialization error could evade agreement checks. | Partially addressed with fixed external canonical hash/byte vectors and corrupt-summary rejection. Full independent serialization/property coverage remains OPEN for a larger schema. |
| R14 | Medium | An embedded harness with a misleading version or an empty upstream suite could appear to validate the pinned protocol. | CassandraUnit assessment records the 3.11.5/5.0.8 embedded-version mismatch and JDK constraints. Shallow model CI precedes Docker. Separate pinned 4.0.5 maintainer smoke requires the exact 16 selected non-skipped methods; synthetic XML controls reject missing, duplicate, unselected, skipped, failed and errored results. Upstream smoke does not cover Atlas batches or disk-sync durability. See [harness assessment](testing-harness-assessment.md). |
| R15 | Medium | A single read immediately after TCP healing conflated network reachability, Cassandra peer membership and authoritative state recovery. Agreement among later reads alone could also miss a collective change from the checked final state. | Both retention scenarios now require all three peers Up/Normal from every node, with bounded membership polling and saved reports. The generated scenario requires a full read within three attempts per coordinator and exact equality with the checked final history state. Preserve driver causes and every read attempt in `retention-healed/`. Earlier runs failed the read-recovery gate; the latest passes. This does not establish the cause of those earlier failures or a recovery-latency SLO. |

## What must happen for a passing run

| Grader | Necessary condition | Observable acceptance criterion | Negative control / limitation |
|---|---|---|---|
| Model/history | Four calls overlap; requests carry recorded dependencies | Independent serial-order verdict plus separate recovery/progress witnesses | Six broken transition/receipt/token stores rejected; black-hole transport rejected by progress gate |
| Cassandra contract | Server reports 4.0.5; replication matches fixture | Actual SERIAL/QUORUM policy and coordinator checks; snapshots, receipts and rejections match contracts | No whole-catalog, large-payload or all-country timing claim |
| Frame faults | Selected batch frame actually intercepted | Before-send loss leaves HEAD unchanged; dropped-response acceptance survives restart and replays original receipt | Latches and state checks; no named mid-Paxos phase |
| Partition | Healthy links first; both directions blocked; host client reachable | Majority progress with bounded exact recovery, minority ambiguity, coherent post-heal state | No-op/one-direction scripts rejected; probe counters are not Paxos-phase evidence |
| Coordinator crash | Batch send witnessed and verified SIGKILL | Surviving DC resolves same operation, later edit does not change original replay | Send witness is not acceptance-phase witness |
| Repair/rejoin | Replica unavailable for accepted writes, then rejoined and repaired | Final LOCAL_ONE on the repaired RF1/DC replica sees final token | Server-phase concurrent-repair gate remains open |
| Retention | Issued signed tickets, controlled deadlines, floor/deletes CAS guard | No expired reexecution; exact younger receipt; explicit UNKNOWN after pruning; actual old row absence | Independent bounded oracle, progress/overlap gates and seven rejected storage mutants; healed full views must match the checked final state. No skew, archive or full SDK claim |

## Evidence and continuation gates

CI artifacts must preserve JUnit/HTML results, histories, reduced commercial mutant
traces and complete bounded retention counterexamples, membership/read witnesses,
`policy.jsonl`, `verified-kills.jsonl`, fault-control scripts/logs, Cassandra logs,
resource snapshot and retention events. A grader fails on missing witnesses;
INCONCLUSIVE is never converted into a pass. Exact-request recovery attempts are
recorded and bounded; recovery success must not be reported as an edit-latency SLO.

## Ranked next work

Prioritize archive and restore safety before production SDK hardening: the SDK
contract depends on what remains recoverable after receipts are pruned or a
snapshot is restored. Keep the existing synthetic retry window until those
protocol decisions are tested.

| Rank | Work | Required evidence before advancing |
|---|---|---|
| 1 | Specify and prove archive-before-prune. | Every accepted receipt being removed has a verified durable archive record. Missing, partial, corrupt or ambiguous archive writes cannot authorize deletion. Exact retries of archive writes are idempotent; archived before/after states remain reconstructable after pruning. |
| 2 | Prove stale-snapshot restore and allocator/floor recovery. | A snapshot predating pruning cannot reopen an expired draft/ticket or reuse an allocated operation identity. Authoring stays fenced until trusted recovery facts are reconciled; unavailable or contradictory facts fail closed. Extend the independent oracle across restore, rather than treating restored state as a fresh trusted initial state. |
| 3 | Harden the SDK retry and signing contract. | Persist the complete Draft/Issued pair across crashes; recover lost allocation/acceptance replies without reminting the request. Define key rotation, lease validation, clock skew and the production retry window, then test their failure paths. |
| 4 | Exercise actual Atlas batches at named Paxos/repair phases. | Instrument the pinned maintainer harness to prove selected phase messages were intercepted. Check state/receipt and floor/delete atomicity; establish repair overlap and control hint/read-repair confounders. |
| 5 | Qualify RF3/DC, then independent hosts and storage growth. | Run the nine-node scaffold on a suitably sized remote runner with fault/resource witnesses. Follow with host/WAN failures, durability and repair/GC/tombstone growth tests. A single hosted runner cannot establish independent-host durability. |
| 6 | Validate provider-to-downstream E2E flows. | API edits, compilation, publication and downstream reconstruction preserve revision identity, effective time and intent-change events, including retries, duplicate delivery and reordering. |

### Immediate POC slice: archive and restore

First define the archive contract and recovery authority in the specification.
An archive ACK must have a stated durability meaning; a second in-memory map is
only a model fixture. Specify which durable facts prevent reuse after restore,
and how authoring is fenced when those facts cannot be established. Do not assume
an atomic transaction across Cassandra and a future archive service.

Add these cases to the independent model first, then the real Cassandra harness:

| Injected condition | Required result / adversarial control |
|---|---|
| Archive unavailable, partial write or lost ACK | No pruning without verified durable coverage. Recover the exact archive write; retain unresolved receipts. A store that prunes anyway must be rejected. |
| Crash after archive persistence, before floor/deletion CAS | Retry safely; archived data is unchanged and pruning remains idempotent. |
| Crash during floor/deletion update | Observe a coherent old or new retained state. Split floor/delete variants must still fail the oracle. |
| Restore a snapshot from before pruning, then replay an old lease | No renewed acceptance and no operation-ID reuse. A store that trusts the old floor without recovery/fencing must fail. |
| Archive record missing or changed during recovery | Fail closed and retain explicit uncertainty; never invent the prior commercial state or declare recovery complete. |

Example: January's offer targets NEW customers; February extends eligibility to
NEW and CHURNED customers. After retry receipts are pruned, downstream audit must
still reconstruct both intents and their effective periods. Restoring a January
snapshot must not reopen an already retired operation. A configuration change
creates a different semantic revision; passage of time alone does not.

The first deliverable is a written archive/restore state machine, independent
oracle extensions and negative controls through the existing Gradle/Make model
gate. Only after that gate passes should the corresponding Cassandra fault cases
be added. A concrete remote archive must eventually validate the claimed durability;
model and Cassandra fixtures alone cannot certify that external service.

PG-COMMIT, PG-CASS and other canonical Atlas proof gates remain UNPROVEN.
