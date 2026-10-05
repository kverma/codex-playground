# Atlas Cassandra POC: adversarial test review

## Decision

Executable commit `521bc1843ae54073ceef8769e97a3f17f5ee2784` passed all four jobs in
[run 37298208324](https://github.com/kverma/codex-playground/actions/runs/37298208324).
Downloaded XML confirms **107 distinct POC cases plus 16 upstream tests**, with no
failures, errors or skips: 51 shallow cases are included in the 90-case
model/single-node/fault job; 17 three-node cases complete the POC total.

All four artifacts were downloaded and inspected. All 107 POC report cases now
show readable Atlas scenario IDs/titles. The [scenario guide](test-scenarios.md)
describes 79 unique goals, simulated boundaries and expected outcomes; shared
contracts run under multiple fixture classes. The description gate passed.
A [separate guide](upstream-test-scenarios.md) explains all 16 upstream methods.

The new Cassandra archive fixture passed six inherited scenarios on one node and
six through dc1/dc2 coordinators on the three-node cluster, plus one real server
SIGKILL/restart case, three wire-fault cases and the signed client-process slice.
Saved archive histories contain fourteen VALID / eleven INVALID traces in the
single-node suite and six VALID / ten INVALID traces in the
three-node suite. Negative traces fail at their intended CERTIFY, PRUNE, RESTORE,
ACCEPT, RECOVER or observed stale-writer boundary. Both suites reject the same
captured stale mutation after sealing, logical restore and recovery; their broken
guard control actually applies the outdated mutation and is then rejected.

This is a real Cassandra persistence fixture with a shared serial reducer and
simulated external archive/authority. A separate serial adapter now integrates
signed drafts. It does not change `RetentionCassandraStore`, restore SSTables or
prove a production global fence.
Full provider API/compile/Kafka/downstream E2E and canonical proof gates remain
unvalidated. All 18 healed retention views still match their checked final states.


This review inspected test code, fault scripts, adapters and stored CI evidence
using safety, availability, Cassandra, chaos, QA and provider-workflow lenses.
It is one code/evidence review, not a claim of independent human or agent sign-off.
Several assertions were too weak to establish their advertised conditions.
The changes below strengthen the harness before broadening product scope.

## Signed-draft process and archive integration — 2026-10-05 UTC

[Run 37298208324](https://github.com/kverma/codex-playground/actions/runs/37298208324) passed all four jobs at
`521bc1843ae54073ceef8769e97a3f17f5ee2784`. All four artifacts were downloaded
and inspected: **107 distinct POC + 16 upstream**, zero failures/errors/skips.
Counts are 90 model/single-node/fault cases (including 51 shallow cases), plus
17 three-node cases. The catalog has 79 unique Atlas scenarios. The existing
readiness boundaries, 14 commercial histories and 18 exact healed retention
views also remain passing. Archive totals are now 14 VALID / 11 intentionally
INVALID traces on one node, and 6 / 10 on three nodes.

The [fixture contract](signed-draft-process-recovery.md) joins the existing signed
Draft/Issued format to the serial Cassandra archive candidate. The signing helper
encodings and RetentionCassandraStore behavior are unchanged; protected final
helpers allow the test adapter to reuse authentication without copying it. New
client JVMs load versioned files and send one request each over a loopback fixture
transport with no automatic retry. This is not the production Atlas API.

The single-node artifact at executable `521bc1843ae54073ceef8769e97a3f17f5ee2784`
contains three checked scenarios and **39 distinct child JVMs**: 14 for allocation
reply loss, 14 for acceptance reply loss and 11 for stale/tampered files. Each lost
reply is tied to the exact successful server message/result and one transport-unknown
child exit. The journal remains unchanged on that loss; the next process recovers
the original Issued pair or receipt. A later edit does not change the old receipt.

Both loss histories then seal/archive/certify/prune two slots, restore an older
logical snapshot, reject authoring while fenced, recover, and reject old draft and
ticket after moving the controlled clock backward. A fresh edit receives sequence
3. Full archive traces are VALID with 24 checked commands each; final floor is 2,
allocation is 3 and the new royalty value is 3000. The stale/tampered history has
seven checked archive commands and preserves royalty 2500 despite the stale edit.

The signed prefixes are independently LINEARIZABLE under RetentionChecker;
three changed-Issued-content controls are NON_LINEARIZABLE. Full archive traces
are independently checked by ArchiveRecoveryChecker. These are separate bounded
oracles, not a combined concurrent protocol proof. Offline artifact inspection
independently recomputed request hashes and HMAC signatures, checked original
read sets and ticket identity, compared exact receipts and verified process exit
counts. This byte/content inspection supplements the CI assertions; it is not an
additional automatic grader.

Tampered draft/Issued content and a changed subject are rejected. Malformed JSON
and an unsupported journal version fail locally with zero additional server
requests. The stale draft retains its old dependencies and conflicts instead of
being refreshed. All journals and exact wire/process outcomes are saved alongside
positive histories and counterexamples.

Adversarial limits: allocation binding and external authority/archive stay in the
parent JVM, and reserve/bind/install are serial steps. A server crash between those
steps is not tested. File force plus atomic rename does not qualify directory
fsync, power loss or a crash during journal replacement. Client exits are real but
occur after reporting the outcome, not during a file write. No server persistence,
concurrent cross-store atomicity, key rotation, new multi-DC signed integration or
whole-database restore is certified by this result.

## Readiness boundary review — 2026-10-05 UTC

[Run 37262063060](https://github.com/kverma/codex-playground/actions/runs/37262063060) passed all four jobs at
`98d814a66cf93730d496b731266b216381695acc`. All four artifacts were downloaded
and inspected: **105 distinct POC cases + 16 upstream**, zero failures/errors/skips.
Counts are 88 model/single-node/fault cases (including the 51 shallow cases) plus
17 three-node cases. The readable scenario catalog now contains 77 unique goals.

Both real scenarios recorded one dc1 coordinator whose wrapped cause was exactly
UnavailableException at SERIAL, two replicas required and one alive. Recovery used
two reads with a successful fresh readiness check between them and returned the
entire expected view. Persistent isolation produced the exact bounded membership
failure message and dc1 up_normal=1; it stopped after one read. Its later isolation
and positive DROP-counter checks passed. Both scenarios healed and completed
readiness cleanup. The positive case took 38.192 seconds and the negative 42.727
seconds in this run; these are harness durations, not Atlas read-latency SLOs.

The [readiness recovery contract](readiness-recovery.md) adds four shallow controls
and two real three-node scenarios. The same bounded read controller now serves
the existing retention history suite and these cases. Only uncertainty permits
another attempt; each retry requires fresh readiness. Definite failures and
failed readiness stop immediately, and three unresolved reads fail progress.

The real cases begin with two accepted edits and one pruned receipt. They record
successful all-node readiness, isolate dc1 with the existing verified network
fault and wait for its actual view to show one Up/Normal and two Down/Normal
replicas. The first read must report quorum loss requiring two replicas with one
alive. A wrapped error must contain only those UnavailableException causes for
one dc1 coordinator; arbitrary timeout/connection failures cannot qualify.

The positive case heals only inside the recovery callback, checks fresh readiness,
then requires full offer/receipt/generation/floor equality and exact retry behavior.
The negative case preserves isolation; fresh readiness must emit the bounded
membership failure and dc1 up_normal=1, with no second read. Cleanup and all
failure evidence remain mandatory. The five-second negative polling budget is
not a wall-clock SLO; each diagnostic command has a separate bound.

Initial [run 37261150315](https://github.com/kverma/codex-playground/actions/runs/37261150315)
at `bbd0c84` passed shallow, maintainer and single-node jobs but failed the two new
three-node cases. The harness incorrectly required a top-level UnavailableException;
the driver returned AllNodesFailedException. Saved nodetool snapshots independently
confirm the intended 3 UN → 1 UN / 2 DN transition in both cases and successful
cleanup. The initial assertion did not preserve the wrapper's nested causes, so
that failed run does not establish the required quorum-read outcome. Commit
`98d814a` records and validates every nested cause and also asserts the exact
persistent-readiness failure message. It does not accept generic wrapped failures.

Integration fixtures now compile in the shallow gate, before hosted Docker jobs
are provisioned. No Cassandra version, consistency, read-attempt bound or full-state
assertion was weakened. This remains RF1 per logical DC on one hosted machine;
production recovery policy, arbitrary flapping and nine-node HA remain unproven.

## Archive wire-loss review — 2026-10-05 UTC

[Run 37259595894](https://github.com/kverma/codex-playground/actions/runs/37259595894) passed all four jobs at
`0a5b3ebb46083b6d3c1a3814f546b47240f9a546`. Downloaded reports contain 84
model/single-node/fault cases (including the 47 shallow cases), 15 three-node cases
and 16 upstream cases: **99 distinct POC + 16 upstream**, zero failures/errors/skips.
All five new wire traces were inspected. The single-node archive totals are now
11 VALID / 11 intentionally INVALID traces; the three-node totals remain 6 / 10.

Independent decoding of saved native request bodies confirmed the bound offer UUID
and pre-write guard in every case, QUORUM and SERIAL semantics, and the three exact
slot deletions in correct prune batches. All three captured replies—including the
partial-pruning mutant—contain `[applied]=true`. The four opposite-cut controls
were rejected at their intended command. The mutant was rejected at command index
18 with `state mismatch: PRUNE`; each correct trace checked all 27 commands.
Native v4 omits the serial-consistency field when using its SERIAL default; the
inspection follows the [Apache protocol specification](https://github.com/apache/cassandra/blob/cassandra-4.0.5/doc/native_protocol_v4.spec), rather than treating absence as LOCAL_SERIAL.
These byte-level inspections supplement the executable assertions and are not an
additional automatic CI gate.

AT-069–071 extend `make grade-faults` to the new archive adapter. Four positive
traces cover seal/prune request loss and reply loss; a fifth trace deliberately
advances the prune floor without deleting receipt rows. The proxy captures the
marked CQL, complete native request body, stream, fault direction and dropped
server reply. Every case requires exactly one matching frame and a real driver
timeout. Reply-loss success additionally requires a ROWS response, changed guard
and exact reloaded state; an ERROR response or an unapplied condition cannot pass.

The oracle receives fresh SERIAL observations, not the reducer's predicted hot
state after an exception. Full terms, receipts, floor, row contents, seals and
fence are compared. Exact retries must converge and then become complete no-ops.
The four correct runs finish logical restore/recovery and reject retired IDs after
a backward clock move. All four deliberately inverted cut labels must fail at the
faulted command. The partial-pruning mutant must actually persist its bad state
and fail specifically at `state mismatch: PRUNE`.

Initial [run 37259287999](https://github.com/kverma/codex-playground/actions/runs/37259287999)
at `6bb30b8` failed integration compilation: `FrameProxy`'s destination Socket
parameter shadowed the target-query field. The shallow and maintainer jobs passed; both Docker jobs stopped at the same
compiler error. Those passes are not archive wire-loss evidence. Commit `0a5b3eb` renames the field; the failed run
is retained rather than retried unchanged or counted as a protocol result.

Scope remains serial, single-node hot-state persistence with simulated external
services. These tests do not establish a concurrent distributed recovery fence,
remote archive durability, SSTable restoration or a named Paxos-phase failure.

## Cassandra archive fixture progress — 2026-10-05 UTC

The [fixture contract](archive-cassandra-fixture.md) separates real storage evidence
from simulated services. HEAD metadata and allocated slot rows share a conditional
Cassandra batch. Sealing, retirement and logical restoration rotate its guard;
physical slot deletion and guard rejection are observed by SERIAL reads. The
independent archive checker consumes those reloaded states rather than predicted
in-memory output.

Nine broken archive variants and a stale-writer guard bypass provide negative
controls. Some deliberately corrupt the simulated authority; others persist bad
Cassandra state. They are not all disk-corruption tests. The server-crash scenario
keeps the external fixture alive while killing Cassandra after certification,
verifies persisted offer/rows on restart, then completes repeated pruning.
That baseline did not inject network-response loss or an internal Paxos phase;
the subsequent wire-loss extension is described above.

The model boundary-loss histories, upstream phase smoke and prior adapter socket
faults retain their own scopes. They are not evidence for unexecuted faults in
this new adapter. Concurrent external-service operations, whole-database restore,
production fencing and bounded recovery-authority storage remain open.

## Previous baseline snapshot — 2026-10-04

| Area | Progress | Remaining boundary |
|---|---|---|
| Build and hosted validation | Cassandra4.0.5, JDK25, Gradle9.1.0 and Make wrappers; all four hosted jobs green. | No personal-machine dependency; this remains a bounded POC. |
| Independent commercial histories | 400 model and 14 Cassandra histories; overlap/progress checks and six rejected storage/transition mutants. | Shared hash/codec assumptions and unobserved server phases remain. |
| Draft identity and retention | Independent drafts receive distinct tickets without refreshing read sets; exact retries and expiry/floor closure covered. R09/R10 addressed for the candidate. | Full SDK persistence, key rotation, skew and restore behavior remain open. |
| Independent retention histories | 200 model histories, including 100 with lost replies; six Cassandra histories; seven rejected/replayed storage mutants. | Trusted initial state and prepared inputs; 24-call/100,000-state bound. |
| Fault and recovery evidence | Verified isolation, kill witnesses, membership recovery and full healed views matched to the checked final state. R15 strengthened. | Earlier ambiguous-read causes and recovery latency are not established. |
| Maintainer harness | Exact 16 selected upstream tests passed; CassandraUnit compatibility assessment completed. | Upstream smoke does not validate Atlas batches or disk-sync durability. |

The previous baseline total was **71 distinct POC cases plus 16 upstream tests**. The 37
shallow cases are included in the 64-case model/single-node/fault job; seven
three-node cases bring the distinct POC total to 71. Generated histories are
iterations inside these cases, not additional JUnit cases.

Latest results were verified from completed CI logs and strict grader conditions.
Artifact downloads initially stalled. The 2026-10-05 continuation downloaded all
four archives and checked their XML and saved history/mutant counts; those match
the totals above. The three-node membership and healed-view evidence was also
checked directly, as detailed below.

## Archive/restore model progress — 2026-10-05 UTC

Executable candidate `ad48e24bcedfcfd073898ad02879a7704a2d8985` adds the
[archive/restore specification](archive-restore-state-machine.md), a separate
state machine and independent serial checker. Its hosted model gate passed 47
JUnit cases. Downloaded XML has zero failures, errors or skips; saved JSON confirms
100 VALID generated traces, 16 VALID before/after boundary traces, nine VALID
contract traces and nine INVALID mutant counterexamples. Tests deserialize and
recheck each saved trace. Generated assertion failures also preserve their seed
and partial trace.

The candidate separates reservation, hot installation, acceptance, sealing,
manifest publication, copying, certification and pruning. Recovery requires
coverage through the external allocation high-water mark, including explicitly
closed unaccepted slots. Old snapshots cannot reset the checker's acceptance
history. Receipt reconstruction follows acceptance order, not allocation IDs.

Adversarial controls reject premature pruning, volatile-ACK/partial-object
certification, split floor/deletion, activating a restored snapshot, rewinding the
authority, resurrecting closed rows, ignoring corruption and omitting a newer
uncertified tail. Complete generated traces must also recover and make progress;
remaining fenced cannot pass their success gate.

**Boundary:** this is a serial in-memory crash model. `RetentionCassandraStore`
still has no audit-archive prerequisite. External archive durability, enforcement
of a global writer fence, authority disaster recovery and concurrent cross-store
interleavings are unproven. The model authority retains every grant/manifest;
bounded production metadata storage is unresolved. An unarchived tail or
uninstalled reservation deliberately blocks recovery, potentially indefinitely.
January/February examples preserve intent contents; business effective dates and
downstream event delivery are not implemented.

The earlier run's three-node archive was downloaded in this continuation:
seven clean JUnit cases, six LINEARIZABLE retention traces, all 18 successful
healed views exactly matching their checked final states, and six membership
reports each showing three UN peers. The other three archives were also downloaded: XML confirms 37 shallow,
64 distinct model/single-node/fault and 16 upstream cases; saved JSON confirms
400 commercial model histories, 200 retention histories and six/seven rejected
commercial/retention mutants. This resolves the earlier artifact-download gap.

## Regression failure and bounded compaction recovery

[Run 37246831178](https://github.com/kverma/codex-playground/actions/runs/37246831178)
passed the 47-case shallow, 74-case model/single-node/fault and 16-test upstream
jobs. The three-node job passed six cases, including all six generated retention
histories, but the older majority-pruning scenario failed on its first SERIAL CAS
write timeout. The overall run failed. Saved XML and the driver cause establish
where it failed; they do not establish the server-side cause.

The follow-up changes only that scenario's recovery gate: at most three compaction
attempts with the same subject, fixed clock and unchanged expired prefix. Every
attempt preserves its outcome, cause or resolved full view. Exact floor, actual
old-row deletion, younger receipt replay and closed old ticket assertions remain.
A model lost-reply control requires recovery without changing the target; an
always-indeterminate control must fail after three attempts. This is bounded
eventual progress, not a first-attempt success or latency guarantee.

The next [run 37247633441](https://github.com/kverma/codex-playground/actions/runs/37247633441)
passed the compaction scenario and both controls, but failed the generated
retention case at seed 2004: dc1 exhausted three immediate SERIAL reads with
`UnavailableException` (two replicas required, one alive). Seed 2005 did not run.
That run is also failed, not a completed six-seed qualification.

The saved readiness/log timeline exposes a sampling race: dc1's 3-UN report was
collected at 00:37:42.899 UTC; dc1 logged both peers DOWN at 00:37:44.685–.688;
the readiness script finished checking dc3 at 00:37:44.999; dc1 logged peers UP
at 00:37:45.696–.735. Sequential membership snapshots cannot establish continuous
readiness. The follow-up requires a fresh bounded membership check between failed
healed-read attempts. It retains the three-read limit, saved causes and exact
equality to the checked final history state. This addresses an observed harness
race without claiming a distributed failure-detector or latency guarantee.

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
| R11 | High | Same-clock tests and session restarts do not validate skew, restored allocator floors, audit archives, GC grace or resurrected rows. | Partially addressed by the separate archive/restore serial model: stale backups, complete coverage, closed IDs, corrupt/missing objects and nine rejected mutants. The new Cassandra persistence fixture adds actual guarded writes, row deletion, logical restore and server restart. Production archive integration, distributed fencing, whole-database restore, authority recovery/bounds, clock skew and repair/GC remain OPEN. Audit retention is separate from retry retention. |
| R12 | High | RF1 per logical DC on one host is being asked to stand in for RF3/DC and independent-host failures. | OPEN. Nine-node scaffold has no execution evidence. Neither hosted runner tests nor Docker SIGKILL qualify WAN SLOs, disks/power failures or an inter-DC link fault on independent hosts. |
| R13 | Medium | The oracle shares request hashing and the snapshot codec with the implementation. A shared serialization error could evade agreement checks. | Partially addressed with fixed external canonical hash/byte vectors and corrupt-summary rejection. Full independent serialization/property coverage remains OPEN for a larger schema. |
| R14 | Medium | An embedded harness with a misleading version or an empty upstream suite could appear to validate the pinned protocol. | CassandraUnit assessment records the 3.11.5/5.0.8 embedded-version mismatch and JDK constraints. Shallow model CI precedes Docker. Separate pinned 4.0.5 maintainer smoke requires the exact 16 selected non-skipped methods; synthetic XML controls reject missing, duplicate, unselected, skipped, failed and errored results. Upstream smoke does not cover Atlas batches or disk-sync durability. See [harness assessment](testing-harness-assessment.md). |
| R15 | Medium | A single read immediately after TCP healing conflated network reachability, Cassandra peer membership and authoritative state recovery. Agreement among later reads alone could also miss a collective change from the checked final state. | Both retention scenarios now require all three peers Up/Normal from every node, with bounded membership polling and saved reports. The generated scenario requires a full read within three attempts per coordinator and exact equality with the checked final history state. Preserve driver causes and every read attempt in `retention-healed/`. The later archive regression exposed membership changing during sequential readiness snapshots. Fresh bounded readiness checks now separate failed read attempts. Both failed and passing runs remain recorded; no continuous-membership or recovery-latency SLO is established. |
| R16 | Medium | The older retention scenario treated one ambiguous majority compaction as definitive failure, although exact recovery is part of the candidate contract. | Added bounded three-attempt recovery at a fixed clock and target, saved attempt/cause/full-view evidence, a lost-reply recovery control and a persistent-ambiguity failure control. Floor, actual deletion, younger receipt and old-ID closure assertions remain mandatory. Server-side timeout cause and latency remain unqualified. |
| R17 | High | A Cassandra-backed model could be mistaken for proof of the external archive and a global restore fence. | Scope explicitly limited: actual CQL persistence, same-partition guarded mutations, observed stale-write rejection and process restart; shared in-memory authority/archive, serial JVM orchestration and logical hot-row restoration. Independent oracle checks reloaded state; real concurrent service failures, old deployments and whole-database restoration remain OPEN. |

## What must happen for a passing run

| Grader | Necessary condition | Observable acceptance criterion | Negative control / limitation |
|---|---|---|---|
| Model/history | Four calls overlap; requests carry recorded dependencies | Independent serial-order verdict plus separate recovery/progress witnesses | Six broken transition/receipt/token stores rejected; black-hole transport rejected by progress gate |
| Cassandra contract | Server reports 4.0.5; replication matches fixture | Actual SERIAL/QUORUM policy and coordinator checks; snapshots, receipts and rejections match contracts | No whole-catalog, large-payload or all-country timing claim |
| Frame faults | Selected batch frame actually intercepted | Before-send loss leaves HEAD unchanged; dropped-response acceptance survives restart and replays original receipt | Latches and state checks; no named mid-Paxos phase |
| Partition | Healthy links first; both directions blocked; host client reachable | Majority progress with bounded exact recovery, minority ambiguity, coherent post-heal state | No-op/one-direction scripts rejected; probe counters are not Paxos-phase evidence |
| Coordinator crash | Batch send witnessed and verified SIGKILL | Surviving DC resolves same operation, later edit does not change original replay | Send witness is not acceptance-phase witness |
| Repair/rejoin | Replica unavailable for accepted writes, then rejoined and repaired | Final LOCAL_ONE on the repaired RF1/DC replica sees final token | Server-phase concurrent-repair gate remains open |
| Cassandra archive fixture | Complete simulated archive coverage and a real guarded HEAD/slot batch | Actual slot deletion; logical restore closure; stale conditional writes rejected before/after recovery; server restart preserves records | Nine archive mutants plus real stale-guard bypass rejected in both suites; external services and global fence remain simulated |
| Archive/restore model | Sealed accepted/unaccepted prefix, verified archive coverage, external authority and modeled fence | Exact restored commercial head; no reopened IDs; complete-coverage progress and replayed counterexamples | Nine mutants rejected; 100 generated serial traces. No real archive, distributed fence or Cassandra integration |
| Retention | Issued signed tickets, controlled deadlines, floor/deletes CAS guard | No expired reexecution; exact younger receipt; explicit UNKNOWN after pruning; actual old row absence | Independent bounded oracle, progress/overlap gates and seven rejected storage mutants; healed full views must match the checked final state. No skew, archive or full SDK claim |

## Evidence and continuation gates

CI artifacts must preserve JUnit/HTML results, histories, reduced commercial mutant
traces and complete bounded retention counterexamples, membership/read witnesses,
`policy.jsonl`, `verified-kills.jsonl`, fault-control scripts/logs, Cassandra logs,
resource snapshot and retention events. A grader fails on missing witnesses;
INCONCLUSIVE is never converted into a pass. Exact-request recovery attempts are
recorded and bounded; recovery success must not be reported as an edit-latency SLO.

## Ranked next work

The [Actions roadmap](github-actions-validation-roadmap.md) ranks experiments that
can stay on small hosted runners. The [nine-node handoff](nine-node-ha-agent-handoff.md)
documents resources, Docker Desktop routing, execution/evidence commands and the
remaining HA witness gaps for another agent. The scaffold has one legacy protocol
case; it does not already run the newer retention/archive contracts at RF3/DC.
Documentation preparation did not execute or qualify the nine-node cluster.

Prioritize archive and restore safety before production SDK hardening: the SDK
contract depends on what remains recoverable after receipts are pruned or a
snapshot is restored. Keep the existing synthetic retry window until those
protocol decisions are tested.

| Rank | Work | Required evidence before advancing |
|---|---|---|
| 1 | Persist concrete archive/authority and draft bindings; the serial signed-draft bridge now passes. | Every accepted receipt being removed has a verified durable archive record. Missing, partial, corrupt or ambiguous archive writes cannot authorize deletion. Exact retries of archive writes are idempotent; archived before/after states remain reconstructable after pruning. |
| 2 | Prove whole-database restore and externally enforced writer fencing; logical restore and stale-CQL guards now pass. | A snapshot predating pruning cannot reopen an expired draft/ticket or reuse an allocated operation identity. Authoring stays fenced until trusted recovery facts are reconciled; unavailable or contradictory facts fail closed. Extend the independent oracle across restore, rather than treating restored state as a fresh trusted initial state. |
| 3 | Harden the SDK retry and signing contract. | Persist the complete Draft/Issued pair across crashes; recover lost allocation/acceptance replies without reminting the request. Define key rotation, lease validation, clock skew and the production retry window, then test their failure paths. |
| 4 | Exercise actual Atlas batches at named Paxos/repair phases. | Instrument the pinned maintainer harness to prove selected phase messages were intercepted. Check state/receipt and floor/delete atomicity; establish repair overlap and control hint/read-repair confounders. |
| 5 | Qualify RF3/DC, then independent hosts and storage growth. | Run the nine-node scaffold on a suitably sized remote runner with fault/resource witnesses. Follow with host/WAN failures, durability and repair/GC/tombstone growth tests. A single hosted runner cannot establish independent-host durability. |
| 6 | Validate provider-to-downstream E2E flows. | API edits, compilation, publication and downstream reconstruction preserve revision identity, effective time and intent-change events, including retries, duplicate delivery and reordering. |

### Immediate POC slice: archive and restore

The model and separate Cassandra persistence slices are implemented and their
hosted gates passed. Signed drafts now join the serial fixture through real client
process restarts. Next persist the server-side bindings and external services,
retaining all negative controls and both independent oracles.
Before durable integration, make the archive contract and recovery authority concrete.
An archive ACK must have a stated durability meaning; a second in-memory map is
only a model fixture. Specify which durable facts prevent reuse after restore,
and how authoring is fenced when those facts cannot be established. Do not assume
an atomic transaction across Cassandra and a future archive service.

Retain these acceptance criteria as the fixture advances to real external services:

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

The written state machine, independent serial oracle and negative controls pass
the model gate. Cassandra logical restore, guarded pruning and server-restart
cases also pass; concrete archive/authority and full restore boundaries are next. A concrete remote archive must eventually validate the claimed durability;
model and Cassandra fixtures alone cannot certify that external service.

PG-COMMIT, PG-CASS and other canonical Atlas proof gates remain UNPROVEN.
