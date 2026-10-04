# Atlas Cassandra POC: adversarial test review

## Decision

The previous revision's 52 tests passed in GitHub Actions run
[37230690045](https://github.com/kverma/codex-playground/actions/runs/37230690045).
That was evidence for bounded Cassandra adapter scenarios, not certification of
Atlas or a full provider API -> compile -> Kafka -> caching-domain E2E flow.

This review inspected test code, fault scripts, adapters and stored CI evidence
using safety, availability, Cassandra, chaos, QA and provider-workflow lenses.
It is one code/evidence review, not a claim of independent human or agent sign-off.
Several assertions were too weak to establish their advertised conditions.
The changes below strengthen the harness before broadening product scope.

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
| R09 | High | Retention admission/recovery tests share `Retention.Base`; there is no independent retention history oracle or model of split floor/delete effects. | OPEN. Scenario assertions, a paused model CAS and real-Cassandra partition/restart checks give bounded evidence, not retention linearizability certification. Extend an independent oracle before combining expiry with general concurrent histories. |
| R10 | High | Two independent retention drafts read the same next allocator slot and can collide as KEY_REUSE before commercial group validation. | Exposed by a new shared model/Cassandra contract. OPEN for production integration: server allocation and SDK request identity must preserve independent-edit behavior without reopening expired operations. Do not describe the separate retention adapter as the full TermGroup edit design. |
| R11 | High | Same-clock tests and session restarts do not validate skew, restored allocator floors, audit archives, GC grace or resurrected rows. | OPEN. Add skew/jump and stale-snapshot recovery experiments, archive durability and repair/GC qualification. Audit retention is separate from retry retention. |
| R12 | High | RF1 per logical DC on one host is being asked to stand in for RF3/DC and independent-host failures. | OPEN. Nine-node scaffold has no execution evidence. Neither hosted runner tests nor Docker SIGKILL qualify WAN SLOs, disks/power failures or an inter-DC link fault on independent hosts. |
| R13 | Medium | The oracle shares request hashing and the snapshot codec with the implementation. A shared serialization error could evade agreement checks. | Partially addressed with fixed external canonical hash/byte vectors and corrupt-summary rejection. Full independent serialization/property coverage remains OPEN for a larger schema. |
| R14 | Medium | An embedded harness with a misleading version or an empty upstream suite could appear to validate the pinned protocol. | CassandraUnit assessment records the 3.11.5/5.0.8 embedded-version mismatch and JDK constraints. Shallow model CI precedes Docker. Separate pinned 4.0.5 maintainer smoke requires the exact 16 selected non-skipped methods; synthetic XML controls reject missing, duplicate, unselected, skipped, failed and errored results. Upstream smoke does not cover Atlas batches or disk-sync durability. See [harness assessment](testing-harness-assessment.md). |

## What must happen for a passing run

| Grader | Necessary condition | Observable acceptance criterion | Negative control / limitation |
|---|---|---|---|
| Model/history | Four calls overlap; requests carry recorded dependencies | Independent serial-order verdict plus separate recovery/progress witnesses | Six broken transition/receipt/token stores rejected; black-hole transport rejected by progress gate |
| Cassandra contract | Server reports 4.0.5; replication matches fixture | Actual SERIAL/QUORUM policy and coordinator checks; snapshots, receipts and rejections match contracts | No whole-catalog, large-payload or all-country timing claim |
| Frame faults | Selected batch frame actually intercepted | Before-send loss leaves HEAD unchanged; dropped-response acceptance survives restart and replays original receipt | Latches and state checks; no named mid-Paxos phase |
| Partition | Healthy links first; both directions blocked; host client reachable | Majority progress with bounded exact recovery, minority ambiguity, coherent post-heal state | No-op/one-direction scripts rejected; probe counters are not Paxos-phase evidence |
| Coordinator crash | Batch send witnessed and verified SIGKILL | Surviving DC resolves same operation, later edit does not change original replay | Send witness is not acceptance-phase witness |
| Repair/rejoin | Replica unavailable for accepted writes, then rejoined and repaired | Final LOCAL_ONE on the repaired RF1/DC replica sees final token | Server-phase concurrent-repair gate remains open |
| Retention | Issued signed tickets, controlled deadlines, floor/deletes CAS guard | No expired reexecution; exact younger receipt; explicit UNKNOWN after pruning; actual old row absence | Separate adapter/scenario specification; allocator collision and independent oracle remain open |

## Evidence and continuation gates

CI artifacts must preserve JUnit/HTML results, histories and reduced mutant traces,
`policy.jsonl`, `verified-kills.jsonl`, fault-control scripts/logs, Cassandra logs,
resource snapshot and retention events. A grader fails on missing witnesses;
INCONCLUSIVE is never converted into a pass. Exact-request recovery attempts are
recorded and bounded; recovery success must not be reported as an edit-latency SLO.

Continue in this order after the strengthened hosted suite is green:

1. Integrate a safe allocator/SDK identity design with independent TermGroup edits,
   and add an independent retention oracle including partial-update mutants.
2. Validate archive-before-prune and recovery of allocator/floor facts across stale
   snapshots; choose the production retry window and key/clock policy.
3. Execute RF3/DC on an adequately sized remote runner; add phase-specific Paxos
   and repair fault witnesses, then independent-host/WAN and storage-growth tests.
4. Add provider API, compile, publication and downstream audit/event reconstruction
   E2E flows as those components exist.

PG-COMMIT, PG-CASS and other canonical Atlas proof gates remain UNPROVEN.
