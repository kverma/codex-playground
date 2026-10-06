# Atlas Cassandra POC: adversarial test review

## Decision

### Archive-bound fencing and timeout recovery — 2026-10-06 UTC

Executable `eed0c99c509d57d7aaaddc5c73638d4000f77742` passed all four jobs in
[run 37497216337](https://github.com/kverma/codex-playground/actions/runs/37497216337).
All four downloaded artifacts were audited: **162 distinct passing tests = 142
POC +4 Atlas-authored overlay +16 upstream**, zero failures/errors/skips.
The 119 single/model/fault cases include the 66 repeated shallow cases; 23
three-node cases complete the POC total. There are 109 scenario IDs. Neither
schedule counts nor repeated shallow execution inflate the distinct test count.

AT-103–109 extend the test-only handoff to COPY/VERIFY/CERTIFY/PRUNE/INSTALL:

- 2,970 writer/recovery schedules and 12,870 two-owner takeover schedules retain
  exact accepted receipt history. All 283,140 exhaustive prefixes are checked;
  these are bounded serialized interleavings, not real process races.
- A focused archived-prefix/new-hot-tail history and three malformed archive
  controls check full receipt coverage, fence binding and endpoint price.
- Seven actually broken implementations fail at their named boundary, with
  passing identical-command controls. An undersized checker returns INCONCLUSIVE.
- A separate environmental counterexample deletes a verified archive object.
  The correct candidate can certify cached proof and then prune away the last
  durable receipt in this three-store model. The checker rejects that exact prune;
  the retained-object control passes. This exposes a required archive retention
  premise, not a proved data-loss bug in the separate fixture with an unbounded
  recovery journal. No production archive provider is qualified.

Two validation failures exposed an incorrect assumption in the older race tests:
[37493947602](https://github.com/kverma/codex-playground/actions/runs/37493947602)
failed AT-102 and
[37494827327](https://github.com/kverma/codex-playground/actions/runs/37494827327)
failed AT-089 on CAS WriteTimeoutException at SERIAL. A timed-out call has an
unknown outcome. The harness now preserves initial results/errors before bounded
exact-request resolution. Definitive results and any durable winner must remain
unchanged; unresolved recovery still fails. Root resolution compares full guards
and payloads, never payload equality alone. These failed runs are not erased by
the corrective run, and their timeouts are not interpreted as aborted writes.

The corrective run's initial race calls all returned definitive results; it did
not naturally reproduce the earlier CAS timeouts. The saved initial/final results
and pre-resolution views agree. The separate deterministic interruption suite
still passes all twelve lost-send/lost-reply/worker-halt cases across 24 JVMs.
Regression audits also retain the 17-JVM authority wire histories, all 3570 prior
fence schedules, quorum/healing witnesses and 256 independent encoding vectors.
All twelve server-phase cut witnesses and the actual partial-prune rejection pass;
an 81.942 ms edit is strictly inside an 82.210 ms repair pause in this run.
These remain bounded fixture claims, not production durability certification.

The [remaining Actions work](actions-only-boundary.md) includes durable actor
captures/proofs and adapter integration; this model does not exhaust hosted tests.
The draft PR remains unmerged and production proof gates remain UNPROVEN.

### Previous fully audited baseline

Executable `843a17c4f579c1e1f095b8564d3d54d50b1427c3` passed all four jobs in
[run 37447187294](https://github.com/kverma/codex-playground/actions/runs/37447187294).
All four downloaded artifacts were audited: **155 distinct passing tests = 135
POC +4 Atlas-authored overlay +16 upstream**, zero failures/errors/skips. The
112 single/model/fault cases include the 59 repeated shallow cases; 23 three-node
cases complete the POC total. The catalog contains 102 scenario IDs. Counts do
not turn the 3570 schedules, twelve wire cuts or twelve phase cuts into extra tests.

### Recovery journal, server-phase and encoding campaign — 2026-10-06 UTC

- AT-095–099 and AT-102 test exact state/receipt journaling. Sixteen saved model
  histories include eight deterministic retry cuts, four actually broken journals
  rejected at their precise boundary, and four passing same-request controls.
  Real Cassandra stores state and accepted receipt in one conditional partition
  batch, with full expected-state conditions. Single/cross-DC sessions preserve
  original receipts after later writes and reopening. Identical overlapping calls
  deduplicate; twelve competing-proposal races across both topologies have one
  winner and exact key-reuse/conflict outcomes.
- Twelve real cuts cover START/FREEZE/PUBLISH/ACTIVATE, each with before-send
  loss, a dropped decoded applied reply and a halted worker. Twenty-four distinct
  JVMs read byte-identical saved inputs. The Python artifact audit checks wire
  effects, original receipts, full state and all 24 per-store histories. Each
  two-handoff trace retains the accepted price edit and rejects a stale writer.
  These handoff plans are trusted and sequential, not a production coordinator.
- Four Atlas-authored overlay tests run beside the original sixteen upstream
  cases. Twelve prepare/propose/commit request/response cuts each record two
  intercepted messages. Exact whole-partition before/after states and final replay
  states agree. The actual HEAD-only prune control leaves the slot present and is
  rejected. A real VALIDATION_RSP pause contains a 100.85 ms write inside 102.96 ms;
  full repair succeeds, three local sentinel views heal, and the edit remains.
  Hints/read repair are disabled for that repair test. This is not disk-streaming
  or independent-host durability evidence.
- AT-100–101 compare 256 separately generated Python encodings/hashes with Java,
  reverse map order, vary non-semantic storage metadata and a semantic royalty,
  and reject ambiguous numeric/eligibility strings without changing state.
- Regression artifact audits again validate both copies of all 3570 fence traces
  and their five exact mutant witnesses; twelve root-only contention races; exact
  minority quorum failures and healed views; and the existing 17-JVM authority
  wire-fault histories. The independent journal auditor is committed as
  `scripts/audit-recovery-journal.py`.

Failed attempts are retained, not hidden by reruns:

1. [37445915897](https://github.com/kverma/codex-playground/actions/runs/37445915897)
   at `7b23e74` stopped in compilation: wildcard imports made `Result` ambiguous.
   Explicit imports fixed the test worker; Cassandra jobs did not run.
2. [37446216601](https://github.com/kverma/codex-playground/actions/runs/37446216601)
   at `e278cf7` passed shallow/single/three. All twelve phase cuts passed, but the
   new partial-prune control extracted CQL at a semicolon inside a quoted payload,
   producing a syntax error before its intended mutation.
3. [37446589497](https://github.com/kverma/codex-playground/actions/runs/37446589497)
   at `c685807` had already started with that helper and retains the same error;
   its encoding and repair-overlap additions passed. Fixing extraction at the
   actual statement boundary yields the final passing control. Hints are disabled
   in the phase fixture too, removing unrelated shutdown-hint noise.

Proposal-request loss resolved as applied in an earlier run and unapplied in the
final run. Both are permitted complete outcomes, not flaky verdicts or inferred
aborts. The audit compares explicit full before/after row sets, then exact replay.

That baseline recorded the [Actions-only limits](actions-only-boundary.md): combined
concurrent archive/prune/restore still needs an integrated persistent coordinator
contract; authority rollback and archive ACK qualification need selected backends;
RF3/DC and independent-host/WAN/storage tests need a different authorized
environment. No claim that all imaginable hosted tests are exhausted is made.
The draft PR remains unmerged; PG-COMMIT/PG-CASS and production E2E remain UNPROVEN.

### Previous verified baseline

Executable `134f124302e3c2f42c93dbf2acafad727794a983` passed all four jobs in
[run 37436678301](https://github.com/kverma/codex-playground/actions/runs/37436678301).
All four downloaded artifacts confirm **124 distinct POC +16 upstream tests**,
zero failures/errors/skips: 104 model/single/fault cases (including the repeated
55 shallow cases), plus 20 three-node. The description gate verifies 94 scenarios.
The preceding [run 37434913605](https://github.com/kverma/codex-playground/actions/runs/37434913605)
also passed all four jobs; the follow-up below tightened negative-control evidence.

### New bounded handoff slice — passed and inspected 2026-10-06 UTC

Evidence review of the first shallow run at `cdbfc59` found that the
WRITE_WHILE_FROZEN mutant could first fail at a stale write after activation,
duplicating STALE_WRITE rather than witnessing a closed-gate write. The bypass is
now restricted to an actually closed gate, and each of the five counterexamples
must assert its exact named unsafe boundary. This is a test-quality correction,
not a newly discovered failure of the correct candidate. Both run records remain.

AT-091–094 add a separate two-store recovery-fence model, not a production adapter.
All 420 one-recovery/two-writer and 3150 two-recovery/one-writer schedules are
enumerated with per-actor order preserved. An independent checker owns its own
receipt ledger and freeze/ownership cuts. Five actually executed broken storage
variants must be rejected and replay from saved traces; the same schedule on the
correct model must pass. Full schedule completion has a separate authoring-progress
check, and bounded-checker INCONCLUSIVE is not accepted.

The model makes an important boundary explicit: root START requests recovery;
only the hot-state FREEZE closes authoring. Accepted edits during that drain window
must be captured. Old ACTIVATE attempts cannot override a newer hot fence. Each
step touches only one store; no cross-partition atomicity is assumed.

A local Java17 diagnostic preceded the pinned JDK25 hosted grade. Both hosted
model copies passed and all 3570 full traces were independently inspected in each
artifact. The 420 writer schedules contain 200 accepted writes, 136 stale outcomes
and 504 fenced outcomes; the 3150 takeover schedules contain 506 accepted writes,
3282 stale outcomes and 2332 fenced outcomes. These are events across independent
schedules, not extra JUnit tests. Every full schedule restores authoring progress.
Each step changes only its intended modeled store; full hot receipt chains remain
intact and checkpoints match the exact captured fence cut. The five actual mutant
witnesses fail at their named boundaries and replay as INVALID. The closed-gate
write is now witnessed at step index 4, separately from stale-after-reopen at index 7.
The checker also validates 38430 whole-execution prefixes per model run; this is
prefix safety, not interrupted-worker recovery. Archive pruning, physical
restore, interrupted actors, lost replies and production writer fencing remain open.
See [the exact model contract and limits](recovery-fence-model.md).

### Earlier root-contention hosted result

Executable commit `aee099ca846a04eb3b4b91b599032e7f598ff75d` passed all four jobs in
[run 37421632336](https://github.com/kverma/codex-playground/actions/runs/37421632336).
Downloaded XML confirms **120 distinct POC cases plus 16 upstream tests**, with no
failures, errors or skips: 51 shallow cases are included in the 100-case
model/single-node/fault job; 20 three-node cases complete the POC total.
The executed catalog contained 90 unique Atlas scenarios. Shared contracts appear
in multiple fixture classes; generated histories do not inflate JUnit case counts.
The description gate passed. All four artifacts were downloaded and inspected.
No failed attempt preceded this result; no rerun was needed.

## Latest authority wire-fault evidence — 2026-10-06 UTC

Inspected AT-087/088 artifacts independently of the green job summaries: 17 distinct
server JVMs, four exact wire cuts, the SELECT response's saved guard/root bytes,
CAS expected/proposed guards and actual applied RESULT, exit75 timeout reports,
unchanged cache bytes and offer state, exact recovered signed identities/receipts,
and three VALID histories (5, 4 and 4 checked commands). Before-send publication
resolves UNCHANGED; lost applied reply resolves PUBLISHED. Later equal-content roots
with different guards resolve UNKNOWN and reject the captured stale proposal.
The wire scenarios passed again in the latest run; the single-node count is now
104, not 104 plus the repeated 55 shallow cases.

## Root contention and isolated authority — passed and inspected 2026-10-06 UTC

AT-089 adds six two-client publication races to the existing single-node and
three-node root contract. Both recorded invocations precede a dispatch barrier;
exactly one conditional publication must apply. Three rounds use identical root
content with distinct guards. Full winner state, loser UNKNOWN resolution and
rejection of both old conditional replays are checked and saved per round.
This witnesses overlapping client calls, not a named internal Paxos interleaving.
Any exception or missing response fails the race; no ambiguous result is counted
as a losing publication.

AT-090 adds a witnessed 2-vs-1 partition in the existing three-node archive grader.
The minority must report UnavailableException (required=2, alive=1) on reads and a
root write. Generic timeout/setup errors do not satisfy this witness. The majority
must publish one exact proposal in at most three attempts while isolation remains.
After fresh membership readiness, all three full root versions must match; the
captured minority proposal must reject, and dc1 must recover publication progress.
All retries, causes, membership/fault logs and final guards are preserved.

Downloaded evidence confirms twelve overlapping races, six per topology, with
exactly one winner in each; six total equal-content/different-guard rounds preserve
winner identity. Both coordinators won at least one three-node round; this is not
a fairness claim. The minority emitted three exact SERIAL UnavailableException
witnesses (two reads, one publication), each required=2/alive=1. Four blocked TCP
edges, membership snapshots, DROP counters and healed readiness are saved. Majority
publication and resumed dc1 publication each completed on attempt 1; all six
post-heal/resumed authoritative views matched the expected full Version on attempt 1.
No latency SLO follows from those bounded successful attempts.

Local `make grade-model` and `make grade-cassandra` could not start because this
workspace lacks Gradle 9.1.0 (also no JDK25/Docker). The hosted shallow job compiled
the new fixtures, and the hosted one/three-node jobs executed them. Description
checks and whitespace checks also passed locally.

These additions exercise the root table alone. They do not integrate simultaneous
root and hot-offer writers, provide a distributed fence, or establish independent
storage-service recovery. Whole-cluster rollback, named Paxos phases, RF3/DC,
independent-host failures, durability and latency proof remain OPEN.

The new Cassandra archive fixture passed six inherited scenarios on one node and
six through dc1/dc2 coordinators on the three-node cluster, plus one real server
SIGKILL/restart case, three wire-fault cases and the signed client-process slice.
Saved archive histories contain fourteen VALID / eleven INVALID traces in the
base single-node suite, plus two VALID / one INVALID durable-server traces and
two VALID / one INVALID split-storage traces, plus one VALID Cassandra-root trace,
and six VALID / ten INVALID traces in the three-node suite. Negative traces fail
at their intended INSTALL, CERTIFY, PRUNE, RESTORE,
ACCEPT, RECOVER or observed stale-writer boundary. Both suites reject the same
captured stale mutation after sealing, logical restore and recovery; their broken
guard control actually applies the outdated mutation and is then rejected.

This is a real Cassandra persistence fixture with a shared serial reducer and
simulated external archive/authority in the base tests. Separate adapters integrate
signed drafts, local immutable checkpoints and a Cassandra root table outside
logical offer restore. They do not change `RetentionCassandraStore`, restore SSTables or
prove a production global fence.
Full provider API/compile/Kafka/downstream E2E and canonical proof gates remain
unvalidated. All 18 healed retention views still match their checked final states.


This review inspected test code, fault scripts, adapters and stored CI evidence
using safety, availability, Cassandra, chaos, QA and provider-workflow lenses.
It is one code/evidence review, not a claim of independent human or agent sign-off.
Several assertions were too weak to establish their advertised conditions.
The changes below strengthen the harness before broadening product scope.

## Cassandra root outside logical offer restore — 2026-10-05 UTC

Executable `f8aced5c4fbb652d8a79307931dc81ca48555420` passed all four jobs in
[run 37327005478](https://github.com/kverma/codex-playground/actions/runs/37327005478).
All four downloaded artifacts confirm **115 distinct POC +16 upstream** with zero
failures/errors/skips: 97 model/single-node/fault cases (including 51 shallow), plus
18 three-node. The catalog has 86 distinct scenarios. No failed attempt preceded
this root-authority result.

The new process case used 17 distinct JVMs, two exact post-CAS/pre-cache halt
witnesses and a VALID 14-command archive history. Saved cache/root references,
content hashes, authority facts, process exits and the exact final receipt state
were independently inspected. One-node and dc1/dc2 CAS witnesses both show that
the old guard fails after content changes away and back; current payload/guard
are unchanged. Earlier 48-process durable and 53-process split-storage artifacts
were re-audited, including the still-unsafe root-rollback negative control.

All 18 successful healed retention views match their checked final states. Seed 2004,
dc1 recorded a first-attempt SERIAL ReadTimeoutException (2 required responses,
0 received), then recovered on attempt 2. This failed read remains in the evidence;
it does not become an extra successful view or a latency qualification.

AT-085 uses a separate Cassandra root table as authority; the local pointer is a
cache. Two actual server halts occur after successful binding/certification root
CAS and before cache replacement. Assertions require unchanged cache bytes and
fresh SERIAL reads proving the new root exists. Exact signed identity and receipt
recovery remain mandatory. After pruning and logical offer restore, replacing the
cache with an older valid root or deleting it must not choose older recovery facts.

AT-086 runs the root CAS contract on one node and across dc1/dc2. It captures an old
guard, changes root content away and back, then submits the captured conditional
update. The update must return not-applied, with the complete current guard/root
unchanged. This witnesses stale root-publication rejection, not general concurrent
archive/authoring correctness. The isolated CAS case uses placeholder object hashes;
it does not claim archive validation for those placeholders.

The earlier root-rollback negative control remains required and unsafe: rolling
back authority itself can reopen old identities. This new boundary only ensures
logical offer restoration and local caches cannot choose an older authoritative
root. Both tables still share a cluster/runner. Whole-cluster restore, stale authority
backups, independent host loss and a production recovery-authority service remain
unqualified. The root and offer partitions are not one atomic transaction, and root
guards do not fence hot-state writers. Orchestration remains serial.

See the [root contract](cassandra-root-authority.md). This does not certify the
canonical Atlas gates or nine-node HA.

## Separate storage reads and root rollback — 2026-10-05 UTC

Executable `223b9963a381886a56772c6e20e2c9d512c38de8` passed all four jobs in
[run 37323949963](https://github.com/kverma/codex-playground/actions/runs/37323949963).
All four downloaded artifacts confirm **112 distinct POC +16 upstream** with
zero failures/errors/skips: 95 model/single-node/fault cases (including 51 shallow)
plus 17 three-node. The catalog has 84 distinct scenarios.

The new slice has **53 distinct server JVMs**: 19 publication, 19 independent-read,
15 root-rollback. Artifacts contain five exact halt witnesses and seven explicit
read failures, two VALID histories (15/12 commands), and one INVALID rollback
history rejected at RECOVER (zero-based index 11). Offline inspection checked every saved
root's actual content hashes, unchanged roots before publication/on read errors,
all process exits, the six domain/fault combinations, and real operation-ID reuse
with a different receipt. The earlier 48-process durable suite was also re-audited.

One existing three-node healed trace (seed 2004, dc1) recorded an INDETERMINATE read:
AllNodesFailedException wrapped UnavailableException, SERIAL requiring 2 replicas
with 1 alive. Its second attempt recovered; all 18 successful coordinator views
exactly match checked final state. The failed attempt remains in the artifact and
is not counted as a successful view.

AT-082–084 separate authority and archive into immutable, content-addressed files.
A root names the exact versions. Each new server JVM verifies and decodes those
same bytes before opening Cassandra. Authority never contains a hidden archive
copy. The test independently makes each referenced file missing, unreadable by
replacing its path with a directory, or stale by substituting a valid older file.
Each rejection must identify the intended read/digest failure and preserve the
entire observed Cassandra hot state and root bytes. Removing archive bytes during
logical recovery must leave the offer fenced until exact bytes return.

Five halts separate staged authority/archive files from root publication. A
pre-publication halt requires both an actual orphan file and unchanged root bytes;
restart must ignore that orphan. Published bindings recover the same identity,
and published archive facts recover the exact receipt after pruning and logical
restore. The [storage contract](split-storage-recovery.md) states the ACK boundary.

**Root rollback remains a demonstrated unsafe condition**, not a solved feature.
The negative control restores an older valid root after pruning and logical
restore, then actually recovers to an empty authority, reuses the retired operation
identity and records a different receipt. The independent serial checker must
reject the trace at RECOVER. Checksums/content addressing verify bytes against a
root; they cannot establish the root's freshness. An independently trustworthy,
rollback-resistant root/authority is still required.

Code review found a verification/use gap in the first revision: authority bytes
were hashed and then reread for decoding. Commit `223b996` removes that second read;
the decoder consumes the exact verified byte array. Earlier run 37323845473 at
`8454675` also passed all four jobs, but was superseded by this code-review
correction, not retried unchanged to obtain green results. Only the final executable's artifacts qualify this slice.

Scope remains one writer, one server fixture and one filesystem with independently
faulted files. There are no independent storage-service processes, network fault
witnesses, distributed root CAS, provider durability or whole-host loss guarantees.
Neither passing positive tests nor the correctly rejected negative control closes
those gaps or certifies canonical Atlas proof gates.

## Durable server-process publication — 2026-10-05 UTC

The one-node job in [run 37301628457](https://github.com/kverma/codex-playground/actions/runs/37301628457)
passed at `df18b03e6339291d309f1e67a9ba97837b62de67`; its downloaded XML confirms
92 cases, including the same 51 shallow cases, with no failures/errors/skips.
The new evidence has **48 distinct server JVMs** (27 publication/recovery, 21
invalid-fact), eight exact halt witnesses, two VALID histories (21/16 commands)
and one INVALID omitted-reservation control, rejected at INSTALL. Offline artifact
inspection independently verified envelope SHA-256, request hashes, ticket HMACs,
unchanged pre-publication bytes, exact recovered identities/receipts and child exits.
The final successful recovery has floor 1, high-water 2 and royalty 3000. The
corrupt-archive scenario ends fenced. No failed hosted attempt preceded this result.

AT-080–081 add actual server JVM exits and a concrete local filesystem contract.
The server reloads disk facts before opening Cassandra; the parent cannot inject
its surviving maps. Reservation and draft binding publish together before slot
installation. Acceptance/pruning still use the real conditional Cassandra batch.
See the [contract](durable-server-process-recovery.md) for exact ACK meaning.

Eight halt boundaries distinguish staged file bytes, published facts and database
mutations: binding before/after rename, installation after the database change,
acceptance after the database change, archive copy before/after rename,
certification after rename and pruning after the database change. Every halt needs
exit 86, an exact phase witness and a real child PID. Before-publication deaths
must preserve the authoritative checkpoint bytes. Published reservations must
recover the same signed identity; accepted requests must recover the exact receipt.

Adversarial cases retain an incomplete blob across restart, remove or truncate the
checkpoint, break its checksum, and remove a binding while recomputing its checksum.
The first four invalid checkpoint cases must exit 65 without changing Cassandra.
A semantically corrupt archive with a valid envelope must still block pruning;
after logical restore it must prevent recovery and keep authoring fenced.

The independent serial archive oracle checks reloaded observations. The deliberately
omitted-reservation control must reject installation. Pre-publication RESERVE/COPY
attempts remain saved staged witnesses, with unchanged durable bytes; they are not
silently counted as committed transitions. The test witness/report is never a
recovery input. These histories supplement the earlier independent signed oracle;
they do not add a combined concurrent cryptographic protocol proof.

Remaining limits: one writer and one filesystem publication unit for authority,
bindings and archive; no independent service failure, remote-provider durability,
checkpoint rollback defense, power-loss qualification, bounded binding retention,
key rotation, actual SSTable restore or global multi-DC fence. This narrows the
server-process gap while leaving those production requirements open.

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
| 1 | Establish rollback-resistant authority/root and qualify independent services; local split-file tests now expose actual identity reuse after root rollback. | Every accepted receipt being removed has a verified durable archive record. Missing, partial, corrupt or ambiguous archive writes cannot authorize deletion. Exact retries of archive writes are idempotent; archived before/after states remain reconstructable after pruning. |
| 2 | Prove whole-database restore and externally enforced writer fencing; logical restore and stale-CQL guards now pass. | A snapshot predating pruning cannot reopen an expired draft/ticket or reuse an allocated operation identity. Authoring stays fenced until trusted recovery facts are reconciled; unavailable or contradictory facts fail closed. Extend the independent oracle across restore, rather than treating restored state as a fresh trusted initial state. |
| 3 | Harden the SDK retry and signing contract. | Persist the complete Draft/Issued pair across crashes; recover lost allocation/acceptance replies without reminting the request. Define key rotation, lease validation, clock skew and the production retry window, then test their failure paths. |
| 4 | Exercise actual Atlas batches at named Paxos/repair phases. | Instrument the pinned maintainer harness to prove selected phase messages were intercepted. Check state/receipt and floor/delete atomicity; establish repair overlap and control hint/read-repair confounders. |
| 5 | Qualify RF3/DC, then independent hosts and storage growth. | Run the nine-node scaffold on a suitably sized remote runner with fault/resource witnesses. Follow with host/WAN failures, durability and repair/GC/tombstone growth tests. A single hosted runner cannot establish independent-host durability. |
| 6 | Validate provider-to-downstream E2E flows. | API edits, compilation, publication and downstream reconstruction preserve revision identity, effective time and intent-change events, including retries, duplicate delivery and reordering. |

### Immediate POC slice: archive and restore

The model and separate Cassandra persistence slices are implemented and their
hosted gates passed. Signed drafts join the serial fixture through real client
process restarts; a separate local checkpoint fixture now covers actual server
exits. Next separate the external failure domains and address checkpoint rollback,
retaining all negative controls and both independent oracles.
Before remote integration, make the provider contract and recovery authority concrete.
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
