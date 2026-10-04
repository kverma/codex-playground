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
This fixture uses whole-subject CAS conservatively: different TermGroups can conflict.
Automatic revalidation/rebase of independent TermGroup edits is a next experiment.

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
partitions, mid-Paxos kills, repair/rejoin under traffic, WAN timing, bounded
interleaving exploration and mutant detection remain outstanding.

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
