# Atlas Cassandra commit POC

An isolated starting point for G2-02A / PG-COMMIT. **Experimental, not certified.**
RevealSwift is a separate project and is not a dependency.

Start with the [plain-language test scenarios](docs/test-scenarios.md): each test
explains its Atlas goal, simulated boundary and required outcome. Scenario IDs and
readable descriptions also appear in test reports and source comments.
The separate [maintainer-test guide](docs/upstream-test-scenarios.md) explains all
16 pinned upstream checks while preserving their original report names.

Next steps: [further GitHub Actions validation](docs/github-actions-validation-roadmap.md)
and the [nine-node laptop agent handoff](docs/nine-node-ha-agent-handoff.md).
The latter is an execution guide for the existing HA scaffold, not evidence that
the nine-node topology has passed.

## Run

Install JDK 25, Gradle **9.1.0**, Docker and Docker Compose v2. Gradle owns all Java
build/test logic; Make is a thin command wrapper. Gradle selects a JDK 25 toolchain.
Cassandra runs the JVM supplied by the pinned **cassandra:4.0.5** image.

```sh
cd atlas-cassandra-poc
make build
make grade-model
make up
make grade-cassandra
make grade-faults           # real frame loss, timeout and SIGKILL/restart
make grade                 # model + integration + fault graders; Cassandra must be running
make down                  # stops this project's containers only
```

Reports: `build/reports/tests/test/index.html`,
`build/reports/tests/gradeCassandra/index.html`; JUnit XML is under
`build/test-results/`. Gradle failures propagate through Make. Integration tests
fail if Cassandra is absent or its reported version is not 4.0.5; they never skip.
Use a disposable database: test subjects and receipts accumulate. `make down`
removes the disposable container; no host data volume is configured.

## Archive and restore candidate

The [signed-draft client-process fixture](docs/signed-draft-process-recovery.md)
joins saved Draft/Issued identities to the serial archive candidate. Separate
client JVMs exercise reply loss and logical recovery; server-side bindings and
external services remain simulated in that fixture. The separate
[durable-server extension](docs/durable-server-process-recovery.md) adds local
checkpoint publication and real server-JVM crash boundaries. The
[split-storage extension](docs/split-storage-recovery.md) adds independent file
read failures and a control exposing the danger of a rolled-back recovery root.

The [Cassandra archive fixture](docs/archive-cassandra-fixture.md) adds real
conditional HEAD/slot mutations, logical restore and delayed-writer guard tests.
Run its cross-coordinator cases with `make three-up && make grade-archive`.
Its base archive and recovery authority remain simulated. Hosted [run 37323949963](https://github.com/kverma/codex-playground/actions/runs/37323949963) passed all four jobs at `223b996`: **112 distinct POC cases plus 16 upstream tests**, including split-storage faults and the rejected root-rollback control.
The archive fault grader now covers actual seal/prune request and reply loss,
exact recovery and a rejected partial-pruning control.
The [readiness boundary tests](docs/readiness-recovery.md) now force membership
loss after readiness passed, verify exact recovery, and reject persistent isolation.
`make grade-model` also compiles integration fixtures before Docker jobs start.

The [archive/restore state machine](docs/archive-restore-state-machine.md) adds a
separate serial model and independent oracle to `make grade-model`: complete
archive coverage before pruning, fenced snapshot recovery, lost replies and nine
broken implementations. It does not yet change the Cassandra retention adapter or
implement a distributed recovery fence. The earlier model-baseline [run 37248385829](https://github.com/kverma/codex-playground/actions/runs/37248385829) passed all four jobs at `bc5c9e0`: 83 distinct POC cases plus 16 upstream tests.
See the [adversarial review](docs/adversarial-test-review.md) for failed-run evidence,
recovery fixes and remaining coverage gaps.

## Candidate and examples

The bounded fixture contains USD cents and eligibility for new/churned customers.
One table uses `(subject UUID, row TEXT)` with HEAD and OP:<operation UUID> rows.
A conditional batch checks the HEAD token and receipt absence, then installs
payload, fresh token and immutable receipt together. Both conditions must pass.
All writes use regular QUORUM and serial SERIAL; authoritative reads use SERIAL
to resolve pending Paxos. No client timestamps, TTL, speculative retry policy or
non-conditional authoritative writes are introduced.

| Identity | Purpose | Example |
|---|---|---|
| Intent SHA-256 | Equality of canonical commercial configuration | March and April match when configuration is unchanged |
| HEAD UUID | CAS concurrency token; changes on each accepted operation | Returning price from $6 to $5 cannot revive an old CAS token |
| Operation UUID + request hash | Exact retry identity | Lost January response resolves its original receipt after a February edit |

January: $5, new customers only. February: $5, new and churned customers.
Eligibility changes the semantic hash. Simply observing January's configuration
later does not change it. Hashes and UUIDs are opaque, not ordering keys. Business
effective-time schedules, provenance and downstream change events are not yet implemented.

The current v3.23 ADR-CONS-004 permits bounded direct payloads in the conditional
batch and supersedes the older exploration handoff's mandatory payload prewrite.
The original two-field fixture uses whole-subject CAS conservatively. The expanded
TermGroup candidate below separately tests logical group versions and physical CAS revalidation.

## Evidence boundaries and ranked next work

1. Current model grader: serial specification, seeded retry histories, concurrent
   same-base conflict, key reuse, ABA and stable semantic hashes. It is not a Paxos simulator.
2. Current Cassandra grader: same contract on real 4.0.5 plus client restart.
   Response loss is simulated by discarding the result after commit; it does not
   exercise a socket timeout or coordinator death during Paxos.
3. Fault grader: actual protocol-v4 proxy drops the batch before send or drops its
   server response, causing a real driver timeout. The committed-response case then
   SIGKILLs Cassandra and restarts it before resolving the original receipt. It does
   not kill during Paxos acceptance; that uncertainty window remains to be tested.
4. Full-HA grader: 3 DC x 3 nodes, RF3/DC, QUORUM=5; whole ingress-DC kill,
   surviving-side commit, second DC kill/minority rejection, restart and receipt
   replay. This grader needs separate execution evidence; single-node CI does not certify it.
5. Expanded candidates: TermGroup read sets, shared model epoch/budgets and bounded
   receipt retention with expiry floors. Next: RF3/DC evidence, audit reconstruction,
   WAN latency, phase-specific Paxos faults and production payload-size limits.

Receipts are retained indefinitely in this disposable fixture. Production bounded
retention and REQUEST_TOO_OLD semantics remain unqualified. No release activation,
HTTP API, Kafka publication or full Atlas schema is implied. Canonical task status
and proof gates remain unchanged. GitHub CI records smoke evidence only.

## Nine-node DC-loss experiment

Use a Linux Docker host with at least 24 GiB memory and enough disk for nine nodes.
The stock GitHub smoke job does not provision this topology. Stop the single-node
fixture first because its port overlaps. Driver discovery uses Docker bridge IPs,
so Docker Desktop/macOS is not a supported full-HA runner yet.

```sh
make down
make ha-up                 # sequential bootstrap, requires nine Up/Normal nodes
make grade-full-ha
make ha-down
```

Report: `build/reports/tests/gradeFullHa/index.html`. Failed fault tests restore
killed nodes in a finally block; use ha-down for final cleanup. Never point these
graders at a shared or production cluster. Every topology uses cassandra:4.0.5.
DC-loss is not a network split, and restart is not repair qualification. Network
partitions and repair/rejoin for RF3/DC, phase-specific mid-Paxos kills, WAN timing
and exhaustive Paxos interleaving exploration remain outstanding. RF1/DC scenario
tests and the bounded application-history checker are described separately below.

The application JVM and test proxy run on JDK25. Native protocol v4 is pinned for
the frame-aware fault proxy. See
[testing harness assessment](docs/testing-harness-assessment.md) for the
CassandraUnit compatibility decision and pinned upstream phase-test grader.
GitHub runs `make grade-model` first, before either Docker job. The separate
`make grade-maintainer` job invokes selected Cassandra4.0.5 maintainer tests in a
JDK11/Ant subprocess; Atlas Gradle and application tests remain on JDK25.

## Three-node GitHub-hosted experiments

The separate `three-node` Actions job runs three real Cassandra 4.0.5 processes,
one per logical DC with RF1/DC and global QUORUM=2. Each heap is capped at 768 MiB;
nodes bootstrap sequentially. The full-HA nine-node topology remains a separate gate.
The pinned image's older JVM hits a cgroup-v2 metrics NullPointerException on the
hosted kernel when nodetool/JMX starts. Multi-node fixtures disable JVM container
auto-detection and explicitly cap heap and processor count; the Cassandra image
version stays unchanged. This workaround is confined to the test containers.
These tests need Linux, Docker, sudo, nsenter and iptables; CI supplies them.

```sh
make three-up
make grade-partition
make grade-coordinator-crash
make grade-repair
make three-down
```

Run separately from the single-node and nine-node fixtures; their host ports overlap.
The partition script enters only dc1's disposable network namespace and drops both
directions of peer traffic. It keeps client access available, checks DROP counters,
and removes the exact rules after the test. The grader checks majority progress,
minority rejection and a coherent view after healing. Majority edits allow at most
three attempts of the identical request when Cassandra reports an indeterminate
outcome; every timeout is recorded, and a successful reply must match the original
operation and request hash. This is bounded recovery evidence, not an edit-latency
SLO measurement.

The crash grader observes an actual batch frame sent to dc1, suppresses its response,
then SIGKILLs that coordinator. A surviving DC resolves the same operation, accepts
a later edit and still returns the original receipt on retry. This explores an
in-flight request; it does not establish which internal Paxos phase was interrupted.

The repair grader kills a replica, writes while it is absent, restarts it, schedules
a full repair command alongside more writes, then repairs again after traffic stops.
It does not witness an internal repair phase overlapping a write; that gate is open. A
LOCAL_ONE read in that replica's RF1 DC must return the final accepted token. This
local diagnostic is not an authoritative application read policy.

CI saves `build/evidence/three-history.jsonl` with UTC invocation/result/fault events,
Cassandra logs, a final resource snapshot, and JUnit/HTML reports. This is a bounded
scenario oracle, not a general linearizability checker or production certification.
One host does not simulate independent power/disk failures or qualify WAN SLOs.

## Cloud execution boundary

Use synthetic POC data and disposable runners. Cassandra client ports are bound
to host loopback; internode ports remain on Docker's private network. Never expose
7000/7001/9042 to the internet. Do not attach production credentials or data to a
fault-testing VM. External VM runners should be ephemeral, run trusted revisions,
use narrowly scoped short-lived credentials where supported, export evidence,
and delete their disks and VM after the job. Provisioning and budget configuration
are outside this POC; the standard hosted Actions job needs no personal machine.

## TermGroup transaction candidate and history checker

`Transactions` adds one subject/option with Economics (USD cents), Eligibility
(NEW or NEW,CHURNED), and Royalty (basis points). Every group has an opaque version.
The subject additionally has a physical generation, admitted model epoch, logical
payload-byte budget, and exact byte summary. The illustrative validation rule is
that churned-customer eligibility requires price <= $5; it is a test fixture rule,
not a universal Atlas product policy.

Requests carry an operation ID, expected model epoch, immutable intended updates,
and the complete validation read set. Economics/Eligibility edits depend on both
groups. Royalty edits depend on Royalty. Model/budget admission requires all groups.
The provider-style edit helper captures these dependencies; omitted dependencies
are rejected. Budget admission and the model epoch fence are engineering fixture
operations, not an authenticated public API.

| Concurrent or historical case | Candidate behavior |
|---|---|
| Economics then independent Royalty from the same snapshot | Revalidate the original read set against a fresh physical generation; retain both changes |
| Price increase vs extending churned eligibility | Changed validation dependency causes a logical conflict; no write skew |
| Independent edits collectively exceed the subject budget | Fresh aggregate validation rejects the overflowing edit |
| Admission advances the model epoch | Old requests conflict even if their group versions remain unchanged |
| Exact accepted operation retried after admission/later edits | Return the original before/after receipt |
| Bounded edit of all three groups | Payload, selectors, model/budget summary and receipt commit together or not at all |

`TransactionCassandraStore` uses the transport's pinned version/replication bootstrap
and a separate `term_subject` table. A single packed HEAD and OP receipt share one
subject partition. The packed snapshot has a versioned codec. A SERIAL read resolves
Paxos; a conditional batch uses serial SERIAL and regular QUORUM. Each failed physical
CAS rereads and revalidates without refreshing the caller's logical read versions.
After 32 physical attempts or a transport ambiguity, return INDETERMINATE and preserve
the exact request for recovery. A second receipt lookup on logical rejection closes
the race with a competing exact retry.

The byte budget counts canonical group values plus group labels; it does not yet
measure CQL/codec/receipt overhead or certify Cassandra cell/batch limits. Receipts
remain unbounded in this disposable POC. Intent hashes bind the fixture's semantic
profile, admitted epoch and canonical values, excluding observation time, physical
generations and group CAS tokens. Full production schema, auth and retention remain
separate gates.

```sh
make grade-model          # contract, checker and deliberately broken implementations
make up
make grade-transactions   # real Cassandra contract, including group/epoch/budget cases
make down
make three-up
make grade-history        # cross-DC histories plus a 2-vs-1 partition
make three-down
```

The independent checker does not call the adapter's admission or transition functions.
It explores serial orders respecting invocation/response precedence and checks reads,
rejections, exact duplicate results and before/after receipts. Timed-out writes remain
pending: the checker considers no effect or a recovered acceptance, rather than
assuming rollback. Workloads explicitly record exact retries to resolve ambiguity.

Bounds: at most 20 operations/history and 100,000 search states. Exceeding either is
INCONCLUSIVE, which fails the grader. Workload seeds reproduce generated requests;
saved intervals and UUIDs reproduce the observed schedule in the checker. The model
grader generates 400 short histories including synthetic lost replies; the real
Cassandra grader generates 14 histories through coordinators in three DCs, including
one under partition. These are bounded examples, not exhaustive distributed-system proof.

Six deliberately broken implementations must be NON_LINEARIZABLE: stale dependency
acceptance, lost independent updates, forgotten receipts, partial multi-group
acceptance, recycled physical generations and recycled group versions. Failing histories are reduced greedily while retaining causal references;
the result is 1-minimal under that constraint, not globally minimal. Saved mutant
counterexamples are deserialized and checked again to verify replayability.

CI evidence: `build/evidence/history-model/`, `history-cassandra/`, and `mutants/`.
Every JSON history contains seed, initial state, invocation/response intervals,
requests, results, verdict and search bound. Unexpected failures produce JUnit errors;
NON_LINEARIZABLE histories also save a reduced counterexample. The same cloud-only
workflow runs these graders; no personal machine or paid VM is required.

## Bounded receipt retention candidate

`Retention` and `RetentionCassandraStore` are a separate experimental adapter;
the earlier transaction/history fixture deliberately keeps its unbounded ledger.
A subject HEAD contains the commercial snapshot, physical metadata generation,
monotonic ticket allocator, expiry floor and last-issued deadline. At most 16
allocated ticket rows (accepted or unresolved) remain logically live. Issuing a
17th ticket rejects with CAPACITY until eligible compaction frees space.

Concurrent clients use a signed prepared draft, then server allocation:

```java
Snapshot observed = store.view().snapshot();
Draft draft = store.prepare(observed, Map.of(Group.ROYALTY, "2000"));
Issued issued = store.issueDraft(draft);
Receipt receipt = store.commit(issued.ticket(), issued.request());
```

The draft binds subject, nonce, allocator anchor, expiry and the original request
hash. Independent drafts from the same snapshot have distinct nonces. The server
assigns a sequence and subject-scoped operation UUID through the metadata CAS,
without refreshing original group read versions. Request hashing excludes the
operation UUID, so allocation preserves its semantic/dependency binding. A retained
nonce/lease index recovers the same ticket on exact issue retry; changed payload
under the same nonce, anchor and expiry is KEY_REUSE. The nonce index is SHA-256 of
that lease identity; HMAC additionally authenticates its request hash.

Clients must preserve the full immutable Draft and Issued records. Re-preparing,
even with the same nonce/payload, can mint a different lease and is a new operation,
not recovery. An unissued lease closes at its signed deadline **or anchor retirement**.
This conservative fence can close an unissued draft before its deadline. Once
issued, exact replay uses the retained ticket row even if its original anchor has
retired; its own sequence/floor decides closure after pruning. Clock rollback cannot
reopen the original pruned lease. Authentication/key rotation remain separate gates.

Tickets bind subject, assigned sequence/operation, request hash and expiry with
HMAC-SHA256. Allocation can extend the deadline to preserve monotonic ticket expiry.
The old `edit`/`issue(Request)` helpers remain only for sequential legacy fixtures;
they still choose the next slot and must not be used for concurrent drafts. The
versioned disposable table is `retained_subject_v2`, with a `draft_id` column;
no production schema migration is implied.

| Situation | Result |
|---|---|
| Retained acceptance receipt, including after later edits | Exact original before/after receipt |
| Deadline passed but accepted receipt is still retained | Exact original receipt |
| Expired ticket without an available receipt, or sequence at/below floor | REQUEST_TOO_OLD; outcome UNKNOWN; no new acceptance |
| Ticket never accepted and later retired | Cannot execute through either commit or issue retry |
| Compaction while retry races | Original receipt or REQUEST_TOO_OLD; commercial state remains unchanged |
| Minority DC during partition | No authoritative floor advance; ambiguity remains INDETERMINATE |
| Majority advances floor and partition heals | Old ticket stays closed; younger retained receipt replays |

Each allocation, acceptance and compaction is guarded by the same metadata CAS.
A SERIAL partition read returns HEAD and bounded ticket/receipt rows. A same-table,
same-partition conditional batch advances the floor and deletes the contiguous
expired prefix together. Failed physical CAS attempts reread the floor, so delayed
requests cannot recreate pruned rows. No TTL or standalone receipt DELETE is used.
Commercial hashes exclude allocator, expiry, pruning and observation time.

The synthetic policy is a one-hour lifetime measured with an injected server clock,
with monotonic issued deadlines even if that clock moves backward. This is a test
parameter, not the selected production retry window. A forward clock jump can expire
tickets early; clock discipline and cross-DC skew remain qualification gates.
The fixture key is explicitly synthetic; secure key provisioning, authorization,
rotation and recovery of key material are not implemented. Restoring an old database
snapshot without allocator/floor recovery is unsupported.

Compaction removes hot receipts without an audit archive. Audit retention and
unknown-outcome resolution are separate, still-open gates. Clients encountering
UNKNOWN must resolve the business outcome and obtain a fresh view before a new edit.
Logical live-row capacity is bounded; tombstone/disk space, GC grace, repair safety,
archive availability and long-running storage growth are not certified here.

`make grade-model` and `make grade-cassandra` include retention contracts; the
single-node contract also checks session restart. `make grade-retention` (after
`make three-up`) checks floor advancement under a 2-vs-1 partition and replay after
healing. CI stores its scenario events in `build/evidence/retention-three.json`.
The retention grader also runs six fresh-subject cross-DC histories, including
one verified partition and post-heal reads of all coordinators. Each coordinator
must return a full authoritative view within three read attempts; every attempt
is saved in `build/evidence/retention-healed/`, including driver causes. Successful
views must equal the oracle-checked final state. After a partition, `three.sh ready`
first requires all three peers Up/Normal in every node\'s `nodetool status` view,
with a bounded 90-second readiness window and saved membership reports. TCP
reachability alone does not satisfy this recovery witness.
These recovery checks sit outside the bounded history and make no latency-SLO claim.
The histories combine
server allocation, concurrent group edits, exact retries, expiry/pruning, closed
old leases and a new sequence after pruning.

`RetentionChecker` separately specifies lease authentication, allocation, capacity,
retained receipts, monotonic floors and atomic prefix deletion. It does not call
`Retention.Base` transitions or `Transactions.check/apply`; commercial transitions
use the existing independent `HistoryChecker`, retaining its accepted-operation
ledger throughout a history, including after storage pruning. Metadata UUIDs are
checked when visible; unobserved intermediate CAS tokens are not reconstructed.
Request hashing/record codecs remain shared and retain the existing canonical-vector
coverage limitation.

Draft preparation occurs outside the checked history: signed input leases and the
initial view are trusted. The oracle checks authentication and allocation/replay;
it does not independently qualify the lease issuer or signing-key lifecycle.

Bounds: 24 calls and 100,000 search states. The injected clock is fixed during
concurrent phases and advances only after joins and exact recovery. Overlapping
calls with different clock values are INCONCLUSIVE. Ambiguous mutations may have no
effect or a coherent full effect; unresolved acceptance without an observable
receipt makes a failed search INCONCLUSIVE rather than an invented violation.
All INCONCLUSIVE results fail workload grading. This is a controlled-clock profile,
not a clock-skew or expiry-at-an-arbitrary-Paxos-phase proof.

The model grader generates 200 histories, half with lost allocation/acceptance
replies. Recorded issue calls and commit calls must overlap, independent leases
must receive distinct tickets, recovery must terminate, at least one acceptance
must exist, and the final authoritative view must succeed. A black-hole transport
has a LINEARIZABLE no-effect history but must fail recovery/progress checks.
Seven actual broken storage hooks must be NON_LINEARIZABLE and replay from saved
JSON: delete without floor, floor without delete, floor rollback, allocator rewind,
omitted receipt, lost independent group update and visible metadata UUID reuse.
Counterexamples are complete bounded histories; no minimality claim is made.

Evidence: `build/evidence/retention-model/`, `retention-cassandra/`,
`retention-mutants/` and `retention-controls/`. Every saved history includes subject,
seed, trusted initial view, requests/results, call intervals, controlled clock
values and bounded verdict. The signing key is the explicitly synthetic test
profile in `RetentionContract`; no production secret is saved.

## Adversarial validation review

See [docs/adversarial-test-review.md](docs/adversarial-test-review.md) for findings,
fixes, negative controls and remaining proof gates. Green CI verifies these bounded
Cassandra adapter scenarios; it is not a full Atlas API/compile/publication E2E run.

History grading now separately requires overlapping recorded initial invocations,
at least one accepted operation, bounded exact-request recovery (up to three attempts),
and a successful final authoritative read. Up to 17 calls fit the 20-call bound.
The four-way gate creates overlapping client calls; it does not force a particular
server-side Paxos schedule. An always-indeterminate transport must fail the progress
checks even when its history is vacuously LINEARIZABLE.

Partition injection first proves peer links are reachable. It then verifies four
blocked TCP probes, one positive INPUT/OUTPUT DROP counter per peer, and continued
host-client port access. Probe traffic contributes to those counters; they prove
network isolation, not a specific Paxos phase. Healing verifies rule removal and
link reachability. No-op and OUTPUT-only injectors must fail these checks in
`make grade-fault-witness`, which runs before the ordinary three-node scenarios.

All successful authoritative adapter calls check the actual driver-reported
coordinator DC and append consistency/coordinator observations to
`build/evidence/policy.jsonl`. The policy guard rejects LOCAL_SERIAL,
LOCAL_QUORUM and automatic idempotent mutation replay. Metadata/bootstrap queries
are outside that authoritative policy log. Timeout routing is not inferred from an
ACK that never arrived. Kill evidence in `verified-kills.jsonl` verifies a running
target followed by stopped state, exit 137 and no OOM before restart.

The prepared-draft candidate separates nonce/lease identity from allocation and
preserves independent TermGroup edits. Its shared model/Cassandra contracts and
independent retention histories qualify bounded fixture behavior only. Production
SDK persistence, archives, skew/restore/GC and RF3/DC qualification remain open.
