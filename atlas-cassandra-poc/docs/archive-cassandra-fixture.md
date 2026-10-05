# Archive and restore on Cassandra: fixture contract

## Atlas behavior being tested

Atlas must not delete the retry evidence for an offer edit before its audit record
is complete. Restoring an older offer must not reopen old requests or lose a newer
accepted change. A writer that computed a valid edit before cleanup must not be
able to apply that edit afterward.

The [scenario guide](test-scenarios.md) describes these tests in user-facing terms.
The scenarios run through `make grade-cassandra` on one node and
`make grade-archive` after `make three-up` across logical DC coordinators. A process
crash between archive certification and pruning runs through `make grade-faults`.
Hosted validation is pending for this addition.

## What is real and what is simulated

| Component | Implementation in this slice | Evidence boundary |
|---|---|---|
| Hot offer and retry records | Real Cassandra4.0.5 table `archive_fixture_v1`; HEAD metadata and one row per allocated slot | SERIAL reads; conditional mutations use QUORUM and SERIAL; actual coordinator DC recorded |
| Acceptance, sealing, retirement and logical restore | One guarded conditional batch updates HEAD and affected slot rows | Rejected stale guards and actual row deletion are observed in Cassandra |
| Commercial/archive transition rules | Reuse `ArchiveRecovery.Model` as a serial candidate reducer | The independent `ArchiveRecoveryChecker` checks reloaded observations and never calls that reducer |
| Archive and recovery authority | Shared in-memory `Storage` fixture surviving a client or Cassandra process restart | No remote service, file fsync, authority failover or whole-runner loss claim |
| Logical backup and restore | Save and replace the bounded hot-state contents through a conditional batch | Not `nodetool snapshot`, SSTable replacement or restoration of a whole cluster |
| Writer fence | Fenced state checked by compliant fixture clients; every hot mutation rotates a Cassandra guard that is not loaded from the logical backup | Proves rejection of captured stale writes; does not revoke arbitrary old deployments or provide an external cross-DC recovery barrier |
| Orchestration | Serial commands under one JVM lock; explicitly delayed writes delivered through real CQL | No general concurrent cross-store protocol, signed-draft allocator integration or production SDK |

The existing `RetentionCassandraStore` remains unchanged. This separately gated
candidate is not silently substituted into the earlier retention contracts.

## Required conditions and adversarial controls

| Scenario | What must be witnessed |
|---|---|
| Incomplete archive | A missing/partial copy blocks certification or pruning; all original slot keys remain in the database |
| Complete archive | Certification precedes pruning; HEAD preserves accepted terms and the selected slot rows are actually absent afterward |
| Old logical snapshot | Authoring is fenced until every allocation is accounted for; restore reconstructs acceptance order rather than allocation order |
| Delayed writer | Capture an admissible acceptance before sealing, then deliver the same guarded write after sealing, restore and recovery; all three CQL conditions must fail |
| Missing recovery facts | A missing archive record, unarchived newer acceptance or unavailable authority keeps the restored offer fenced |
| Broken implementations | Nine existing archive mutants run through the persistence adapter; their actual reloaded state/external observations must fail the independent checker |
| Broken writer guard | Deliberately use today's guard for an outdated prepared change; the write must actually apply and the resulting observed history must be rejected |
| Server process loss | A verified SIGKILL occurs after archive certification but before pruning; after restart the same offer and all retained rows must survive, then idempotent pruning completes |

The nine mutants span simulated external-service mistakes and real hot-state
mistakes. They do not all mutate Cassandra: for example, trusting a volatile ACK
corrupts the simulated authority's certification. The guide and evidence retain
that distinction. Real driver exceptions fail the test; they are never converted
into successful negative-control results.

This slice does not inject a socket loss inside the new archive adapter or a named
Paxos phase. The earlier frame-fault suite covers a different adapter. The model's
16 boundary-loss cases cannot be relabeled as network coverage for this fixture.

## Evidence and next gates

Each scenario saves commands, outcomes, reloaded full state and checker verdict
to `build/evidence/archive-cassandra-single/` or `archive-cassandra-three/`.
Saved traces are deserialized and rechecked. Stale-write outcomes are separate
witnesses; process kills use the existing independent kill witness. The checker
must return VALID for correct runs and INVALID for broken implementations.
INCONCLUSIVE does not satisfy either gate.

Next integrate signed drafts, choose an archive/authority service contract, and
design a fence that survives the actual database disaster-recovery boundary.
Then test concurrent archive/restore operations, service outages and persistence,
whole-snapshot restoration, and controlled mid-Paxos faults. The authority still
retains unbounded metadata. PG-COMMIT and PG-CASS remain unproven.
