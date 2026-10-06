# Further validation in GitHub Actions

## Current evidence

Executable `843a17c4f579c1e1f095b8564d3d54d50b1427c3` passed
[run 37447187294](https://github.com/kverma/codex-playground/actions/runs/37447187294):
135 distinct POC cases, four Atlas overlay cases and sixteen upstream tests
(155 total, no failures/errors/skips; all four artifacts audited). Models, one-node Cassandra, verified
process/network faults and a three-node RF1-per-DC fixture already run remotely.
The new archive fixture has real Cassandra hot state but simulated external
archive/authority in its base tests; the new server-process extension uses one local
root table outside logical offer restoration, with separately read archive/authority
files. Archive/offer service orchestration remains serial; root races and the
bounded handoff model are separate. These are bounded POC results.
The exact-operation journal adds actual lost request/reply and halted-worker cuts;
it does not remove the serial planning limitation. See the
[campaign boundary](actions-only-boundary.md) before treating further fixture work
as production qualification.

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

The follow-on [archive-bound fence model](archive-fence-model.md) now combines
copy, verify, certify, prune, install and activation with the fence. Its hosted
shallow run and independent artifact audit cover 2970 writer schedules and 12870
two-owner schedules, seven unsafe candidates, three malformed copies and a
prefix-plus-hot-tail recovery. An explicit lost-object counterexample shows the
retained archive premise failing at PRUNE. This narrows the rank-5 design gap;
it still has no persistent actors, allocation gaps or real concurrent adapters.

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
Rank 5 also has the [exact-operation journal extension](recovery-journal.md),
including interrupted workers and real Cassandra request/reply cuts. This still
does not combine concurrent archive/certify/prune/restore into one persistent
coordinator. Ranks 6/7 now have passed, artifact-inspected
[Atlas-authored server-harness slices](maintainer-atlas-overlay.md) in run
37447187294: twelve counted batch message cuts, an actual split-prune control and
a witnessed repair-validation-response pause. Rank 8 now has 256 independent
Python encoding vectors and malformed-input checks for the **existing** bounded
profile (AT-100–101). Larger fields and rank 9 require schema decisions.
Give each addition an independent failure witness and a negative
control; “the command succeeded” or “the suite eventually passed” is insufficient.

| Rank | Atlas question | Experiment | Required evidence | Suggested layer |
|---|---|---|---|---|
| 1 — passed | Does archive-backed cleanup recover its exact outcome after a lost reply? | Apply the real frame proxy to the **new archive adapter**, dropping a batch before send and dropping a verified server reply after acceptance; exercise seal and prune boundaries. | Intercept the intended batch; retain the exact request/target; compare full offer, floor and actual slot keys before/after recovery. A partial floor/delete implementation must fail. The new tests cover this adapter; earlier socket tests remain separate. | One node, `grade-faults` extension |
| 2 — passed | Does recovery handle membership changing during readiness checks? | Deterministically arrange an Up/Normal snapshot followed by a peer-down transition before a SERIAL read; test eventual recovery and persistent unavailability separately. | Witness both transitions and a failed read; prove fresh readiness checks occur between bounded retries. Exact final-state equality remains mandatory. A permanently unavailable supplier must fail progress. | Shallow controller tests, then three nodes |
| 3 — fixture passed | Does a real saved draft remain the same edit across process restarts and archive recovery? | Integrate the signed Draft/Issued path with the archive candidate; persist the complete pair and restart the client process after allocation/acceptance reply loss. Include changed payloads, retired anchors and backward controlled clocks. | Same identity, original dependencies and exact receipt; no silent reminting or stale draft reopening. Distinguish process persistence from whole-host durability. | Model plus one/three nodes |
| 4 — local slice passed | Is a partially written audit record ever mistaken for a durable one? | Define a storage contract; run a local disk-backed or object-store service fixture with partial writes, process restart, unavailable reads and corrupt/missing records. Keep authority separate. | Document what its ACK means, verify complete bytes and binding, preserve facts across **service process** restart, block pruning on uncertainty. A local emulator cannot qualify a remote provider's persistence guarantee. | Separate one-node + service job |
| 5 — expanded model slice | Can concurrent cleanup and restore violate an offer's edit history? | The archive-bound model checks 15,840 phase schedules and exact conservation; next persist actor proofs/requests and bridge real adapters, including reservation gaps. | The model uses independent accepted-history reconstruction. Real concurrent adapters still need measured intervals and a bounded concurrent oracle; model interleavings are not measured thread overlap. | Shallow passed; integrated adapters still open |
| 6 — bounded slice passed | What happens to Atlas-shaped conditional batches at specific Paxos phases? | Pinned overlay runs HEAD/receipt and floor/delete shapes through six request/response cuts each. | Twelve exact verb hit counters; complete before/after partitions on all coordinators; exact reissue; actual broken-prune rejection. Adapter orchestration and disk durability remain outside this overlay. | Existing JDK11/Ant job |
| 7 — bounded slice passed | Does repair really overlap an edit, rather than merely run nearby? | Hold an actual VALIDATION_RSP while an edit completes; disable hints and read repair. | Strictly nested monotonic interval, successful parent repair, local missing-row healing and exact retained edit. This is a pending validation-response boundary, not a claimed disk-streaming callback. | Existing maintainer harness |
| 8 — current profile passed | Can independent encoders disagree about an intent ID? | 256 independent Python byte/hash vectors; reversed Java map insertion; changed metadata and royalty; malformed admission values. | Exact wire/hash equality, metadata invariance, semantic-change distinction and state-preserving rejection. Larger configurations/schedules still await fields and contracts. | Shallow |
| 9 | Can downstream teams reconstruct historical targeting from events? | Once the API/event schema exists, run producer/compiler/consumer fixtures with duplicates, reordering, late delivery and January/February eligibility changes. | Original intent, provenance and effective periods remain distinguishable; unchanged configuration does not create a time-driven revision. A local broker proves fixture behavior, not production deployment. | Dedicated service job |

For seeded histories, add a small deterministic PR set and a larger explicitly
budgeted scheduled/manual matrix. Record each seed, fault witness, bounds, timing,
result and resource profile. Keep every failed attempt visible; avoid “retry until
green.” Bound jobs, use fail-fast only where it will not discard useful independent
evidence, upload artifacts on failure, and clean up only the job's own containers.
All additions extend existing graders; no extra runner job or nine-node execution
was added. Rank 5's persistent concurrent adapters and rank 9 remain
unexecuted. Ranks 6–8 have the narrow passed slices above. The new archive-fence
model binds coverage and installation to frozen generations; next persist the
actor proofs and exact phase requests and integrate the tested storage pieces. The
[Actions-only campaign boundary](actions-only-boundary.md) separates further
possible bounded tests from claims requiring new contracts or infrastructure.
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
