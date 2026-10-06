# Boundary of this Actions-only campaign

This is a boundary of the **current specified POC**, not a claim that no further
test can ever be written in GitHub Actions. The hosted workflow can run more
bounded models, message cuts and seeded histories. Increasing their number cannot
establish a storage/service guarantee that the fixture does not implement.

## What is executable without new infrastructure

The existing workflow exercises bounded intent/transaction models, recorded
histories, one-node Cassandra wire/process faults, three-node RF1-per-DC failure
and repair scenarios, exact recovery journals, and a pinned upstream server
harness. The new overlay targets twelve Atlas-shaped Paxos message cuts and a
witnessed repair pause. Independent Python vectors cover the current bounded
commercial schema. Exact run results and failed attempts belong in the
[adversarial review](adversarial-test-review.md), not inferred from this inventory.

## What requires a decision or a different environment

| Remaining question | Why another standard hosted pass is insufficient | Required next input |
|---|---|---|
| Combined concurrent accept/archive/certify/prune/restore | The serial archive fixture holds an in-process lock; the separate handoff journal uses trusted sequential plans. Neither is a persistent multi-service coordinator. | Specify/approve the integrated coordinator contract: durable operation allocation, frozen-generation coverage certificate, install/activate proof and journal retirement. Then implement it and continue bounded Actions tests. |
| Authority survives offer/cluster rollback | Separate partitions on one Cassandra cluster can roll back together. | Select the rollback-resistant authority backend and state which restores are permitted to affect it. |
| Archive ACK survives real loss | Filesystem checkpoints and emulators cannot qualify a provider's ACK, retention, corruption recovery or independent failure domain. | Select archive backend, required ACK/immutability contract, and a disposable authorized test environment. |
| RF3 in each of three DCs | Existing nine-node scaffold is outside the current standard-runner resource budget; three nodes are RF1/DC, not a substitute. | An approved sufficiently sized runner or Linux Docker environment; planning allowance is at least 24 GiB available. |
| Independent host/WAN/storage failures and latency | All Docker nodes share one host; in-JVM nodes share one process and skip disk sync. | Independent hosts/failure injection and explicit durability/latency acceptance criteria. |
| Larger commercial identity and downstream event contracts | The current schema is deliberately bounded; no production producer/compiler/consumer event schema exists here. | Define configuration/schedule/event fields, effective-time rules and consumers before assigning compatibility verdicts. |

No cloud services, paid/larger runners, credentials or external account resources
are provisioned by this campaign. The draft PR is not merged. More testing of the
same bounded profile remains possible, but the next end-to-end claim requires
these contracts/infrastructure rather than a larger count of green fixtures.

PG-COMMIT, PG-CASS, independent-host durability, RF3/DC and Atlas production E2E
remain UNPROVEN.
