# Further validation in GitHub Actions

## Current evidence

Executable `134f124302e3c2f42c93dbf2acafad727794a983` passed
[run 37436678301](https://github.com/kverma/codex-playground/actions/runs/37436678301):
124 distinct POC cases and 16 upstream tests. Models, one-node Cassandra, verified
process/network faults and a three-node RF1-per-DC fixture already run remotely.
The new archive fixture has real Cassandra hot state but simulated external
archive/authority in its base tests; the new server-process extension uses one local
root table outside logical offer restoration, with separately read archive/authority
files. Archive/offer service orchestration remains serial; root races and the
bounded handoff model are separate. These are bounded POC results.

The root wire-fault extension now passed four exact read/publication cuts with
17 server processes and checked recovery traces. AT-089 same-base competing root publications and AT-090 root
quorum loss/majority progress/healing also passed: twelve witnessed races across
two topologies, three exact minority quorum-loss errors and six matching
post-heal/resumed full root views. These are root-only tests. Rank 5 below
still requires an integrated concurrent archive/prune/restore oracle; only the
separate handoff slice below has passed.

## Ranked next experiments on standard hosted Linux runners

Rank 5 now has a **passed, artifact-inspected bounded handoff model**:
AT-091–094 enumerate 3570 per-store interleavings and five broken-fence controls.
This does not implement concurrent archive/prune/restore operations, so the larger
rank-5 gate remains open. See [recovery-fence model](recovery-fence-model.md).

Rank 1 is now implemented and passed: AT-069–071 cover four actual wire-loss
boundaries and a real partial-pruning mutant. Saved byte-level witnesses and
full-state recovery were inspected; see the [review](adversarial-test-review.md).
Rank 2 is also implemented and passed: AT-072–077 add four shallow recovery
controls and two witnessed three-node membership boundaries. Recovery returned
exact state after two reads; persistent isolation stopped after one read and a
failed fresh readiness check. See the [contract](readiness-recovery.md).
Rank 3 has passed at the serial fixture level: AT-078–079 launch 39 child JVMs,
recover the same signed identities/receipts after actual reply loss, and exercise
logical archive recovery, stale dependencies and tampered journals. See the
[contract](signed-draft-process-recovery.md). The server-side binding map and
external services remain in memory in that original fixture.
Rank 4 has a passing **local single-writer slice**: AT-080–081 persist binding and
reservation together and use 48 real server JVMs to test eight publication/database
crash cuts, damaged checkpoints and incomplete/corrupt archives. See the
[contract](durable-server-process-recovery.md). Its 21/16-command histories validate;
the omitted-reservation control fails at INSTALL. This is one atomic filesystem
publication unit, not independent authority/archive services. The
[split-file extension](split-storage-recovery.md) passed AT-082–084 using 53 server
JVMs, five halt cuts, seven exact read failures and an actual retired-ID reuse
control rejected at RECOVER. It still uses one
filesystem and does not qualify separate storage services. Next establish a
rollback-resistant authority/root contract, then test independently hosted services
and unavailable remote reads. The [Cassandra-root extension](cassandra-root-authority.md)
passed a 17-process recovery history with two root-publication halt cuts and stale
publication CAS on one node and across dc1/dc2. Local cache rollback no longer
chooses authority; whole-cluster rollback resistance remains open.
Rank 5 has only the handoff model slice above; ranks 6–9 remain proposals.
Give each addition an independent failure witness and a negative
control; “the command succeeded” or “the suite eventually passed” is insufficient.

| Rank | Atlas question | Experiment | Required evidence | Suggested layer |
|---|---|---|---|---|
| 1 — passed | Does archive-backed cleanup recover its exact outcome after a lost reply? | Apply the real frame proxy to the **new archive adapter**, dropping a batch before send and dropping a verified server reply after acceptance; exercise seal and prune boundaries. | Intercept the intended batch; retain the exact request/target; compare full offer, floor and actual slot keys before/after recovery. A partial floor/delete implementation must fail. The new tests cover this adapter; earlier socket tests remain separate. | One node, `grade-faults` extension |
| 2 — passed | Does recovery handle membership changing during readiness checks? | Deterministically arrange an Up/Normal snapshot followed by a peer-down transition before a SERIAL read; test eventual recovery and persistent unavailability separately. | Witness both transitions and a failed read; prove fresh readiness checks occur between bounded retries. Exact final-state equality remains mandatory. A permanently unavailable supplier must fail progress. | Shallow controller tests, then three nodes |
| 3 — fixture passed | Does a real saved draft remain the same edit across process restarts and archive recovery? | Integrate the signed Draft/Issued path with the archive candidate; persist the complete pair and restart the client process after allocation/acceptance reply loss. Include changed payloads, retired anchors and backward controlled clocks. | Same identity, original dependencies and exact receipt; no silent reminting or stale draft reopening. Distinguish process persistence from whole-host durability. | Model plus one/three nodes |
| 4 — local slice passed | Is a partially written audit record ever mistaken for a durable one? | Define a storage contract; run a local disk-backed or object-store service fixture with partial writes, process restart, unavailable reads and corrupt/missing records. Keep authority separate. | Document what its ACK means, verify complete bytes and binding, preserve facts across **service process** restart, block pruning on uncertainty. A local emulator cannot qualify a remote provider's persistence guarantee. | Separate one-node + service job |
| 5 | Can concurrent cleanup and restore violate an offer's edit history? | Extend the serial archive candidate/oracle to recorded overlapping accept, seal, copy, certify, prune and restore operations, including unarchived tails and reservation gaps. | Actual overlap intervals and an independent bounded concurrent oracle; negative controls for premature deletion, fence bypass and discarded tails. Do not reuse the serial checker as if it proved concurrent behavior. | Shallow first, then three nodes |
| 6 | What happens to Atlas's actual conditional batches at specific Paxos phases? | Adapt the pinned 4.0.5 in-JVM maintainer harness to actual HEAD/receipt and floor/delete CQL; drop or pause prepare/propose/commit messages. | A counter/witness proves the chosen message and phase were hit; exact receipts and atomic state survive resolution. Retain the upstream disk-sync and transport limitations. | Separate JDK11/Ant job |
| 7 | Does repair really overlap an edit, rather than merely run nearby? | Introduce witnessed server-side repair pause points and release a measured write workload during that phase. Control hints and read-repair confounders. | Server-phase overlap, completed repair and authoritative/local replica checks; fail if overlap never occurs. | Maintainer harness or three nodes |
| 8 | Can independent encoders disagree about an intent ID? | Expand independent canonicalization vectors/property tests for larger configurations, schedules and malformed values as those fields are introduced. | Separately produced bytes/hashes; configuration equality stable over observation time; relevant changes produce different IDs. Do not compare one shared implementation with itself. | Shallow |
| 9 | Can downstream teams reconstruct historical targeting from events? | Once the API/event schema exists, run producer/compiler/consumer fixtures with duplicates, reordering, late delivery and January/February eligibility changes. | Original intent, provenance and effective periods remain distinguishable; unchanged configuration does not create a time-driven revision. A local broker proves fixture behavior, not production deployment. | Dedicated service job |

For seeded histories, add a small deterministic PR set and a larger explicitly
budgeted scheduled/manual matrix. Record each seed, fault witness, bounds, timing,
result and resource profile. Keep every failed attempt visible; avoid “retry until
green.” Bound jobs, use fail-fast only where it will not discard useful independent
evidence, upload artifacts on failure, and clean up only the job's own containers.
Ranks 1–4 extend existing graders; no extra runner job or nine-node execution
was added. Rank 5's full archive/prune/restore concurrency and ranks 6–9 remain
unexecuted. Next extend handoff recovery to interrupted actors and exact replies,
then bind archive coverage and snapshot installation to the frozen generation.
Rank 4 narrows server-process persistence
only; independent service durability,
concurrent allocation and partial client journal-write crashes remain open.

## Where standard runners stop being the right tool

GitHub's [official runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
checked 2026-10-05 lists standard Linux x64 runners at 4 vCPU/16 GB RAM/14 GB SSD
for public repositories and 2 vCPU/8 GB RAM/14 GB SSD for private repositories.
Recheck the workflow's actual resource report and repository visibility before
budgeting. Larger runners can still be orchestrated by Actions, but availability
and billing depend on the account; this guide does not provision one.

The nine-node compose file sets a 1 GiB heap per node: nine heaps alone require
about 9 GiB, before native memory, page cache, Docker and the test JVM. Its planning
target is at least 24 GiB available to the Docker execution environment. This is
an engineering allowance, not a measured minimum. Do not shrink heaps or enable
swap merely to make a green run fit and then treat it as equivalent evidence.

| Environment | Useful qualification | Still not established |
|---|---|---|
| Standard hosted runner | More model, one-node and three-node correctness/fault evidence | RF3/DC capacity, independent hosts, real WAN/storage failures |
| Larger hosted runner or adequately sized local Linux environment | Nine-node RF3/DC quorum and bounded failover tests | Host independence, regional failure, power loss, production latency |
| Independent remote hosts and controlled network/storage faults | Stronger host/WAN/recovery experiments | A complete production certification without its own acceptance criteria |

Use the [nine-node agent handoff](nine-node-ha-agent-handoff.md) for the local path.
Keep Atlas PG-COMMIT/PG-CASS and provider-to-downstream E2E unproven until their
specific gates have execution evidence.
