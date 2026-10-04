# Atlas Cassandra commit POC

An isolated starting point for G2-02A / PG-COMMIT. **Experimental, not certified.**
RevealSwift is a separate project and is not a dependency.

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
5. Next: TermGroup read sets, shared model epoch/budgets, bounded receipt retention
   and expiry floors, audit reconstruction, WAN latency and payload-size limits.

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
the frame-aware fault proxy. CassandraUnit is unnecessary for these real-server tests.

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
minority rejection and a coherent view after healing.

The crash grader observes an actual batch frame sent to dc1, suppresses its response,
then SIGKILLs that coordinator. A surviving DC resolves the same operation, accepts
a later edit and still returns the original receipt on retry. This explores an
in-flight request; it does not establish which internal Paxos phase was interrupted.

The repair grader kills a replica, writes while it is absent, restarts it, runs full
repair concurrently with more writes, then repairs again after traffic stops. A
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

Four deliberately broken implementations must be NON_LINEARIZABLE: stale dependency
acceptance, lost independent updates, forgotten receipts, and partial multi-group
acceptance. Failing histories are reduced greedily while retaining causal references;
the result is 1-minimal under that constraint, not globally minimal. Saved mutant
counterexamples are deserialized and checked again to verify replayability.

CI evidence: `build/evidence/history-model/`, `history-cassandra/`, and `mutants/`.
Every JSON history contains seed, initial state, invocation/response intervals,
requests, results, verdict and search bound. Unexpected failures produce JUnit errors;
NON_LINEARIZABLE histories also save a reduced counterexample. The same cloud-only
workflow runs these graders; no personal machine or paid VM is required.
