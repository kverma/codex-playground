# Archive and restore on Cassandra: fixture contract

## Atlas behavior being tested

Atlas must not delete the retry evidence for an offer edit before its audit record
is complete. Restoring an older offer must not reopen old requests or lose a newer
accepted change. A writer that computed a valid edit before cleanup must not be
able to apply that edit afterward.

The [scenario guide](test-scenarios.md) describes these tests in user-facing terms.
The scenarios run through `make grade-cassandra` on one node and
`make grade-archive` after `make three-up` across logical DC coordinators. A process
crash between archive certification and pruning, plus targeted seal/prune wire
loss, runs through `make grade-faults`.
Hosted [run 37259595894](https://github.com/kverma/codex-playground/actions/runs/37259595894) passed at
`0a5b3ebb46083b6d3c1a3814f546b47240f9a546`: 99 distinct POC cases plus 16
upstream tests. All five new wire traces were inspected, including bound offer and
guard bytes and `[applied]=true` in each captured reply. Saved archive traces total
11 VALID / 11 intentionally INVALID on one node and 6 / 10 on three nodes.

## What is real and what is simulated

| Component | Implementation in this slice | Evidence boundary |
|---|---|---|
| Hot offer and retry records | Real Cassandra4.0.5 table `archive_fixture_v1`; HEAD metadata and one row per allocated slot | SERIAL reads; conditional mutations use QUORUM and SERIAL; actual coordinator DC recorded |
| Acceptance, sealing, retirement and logical restore | One guarded conditional batch updates HEAD and affected slot rows | Rejected stale guards and actual row deletion are observed in Cassandra |
| Commercial/archive transition rules | Reuse `ArchiveRecovery.Model` as a serial candidate reducer | The independent `ArchiveRecoveryChecker` checks reloaded observations and never calls that reducer |
| Archive and recovery authority | Shared in-memory `Storage` fixture surviving a client or Cassandra process restart | No remote service, file fsync, authority failover or whole-runner loss claim |
| Logical backup and restore | Save and replace the bounded hot-state contents through a conditional batch | Not `nodetool snapshot`, SSTable replacement or restoration of a whole cluster |
| Writer fence | Fenced state checked by compliant fixture clients; every hot mutation rotates a Cassandra guard that is not loaded from the logical backup | Proves rejection of captured stale writes; does not revoke arbitrary old deployments or provide an external cross-DC recovery barrier |
| Orchestration | Serial commands under one JVM lock; explicitly delayed writes delivered through real CQL | No general concurrent cross-store protocol or production SDK; the separate signed-draft adapter has its own fixture scope |

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
that distinction. Unexpected driver exceptions fail the test. The wire-loss cases require an exact
`DriverTimeoutException` plus the matching proxy witness and authoritative state;
an arbitrary exception cannot satisfy a negative control.

## Seal/prune wire-loss extension

AT-069–071 target the new adapter's actual conditional batch using an offer UUID
and operation marker. For both sealing and pruning, the proxy drops either the
request before forwarding or the server's response after forwarding. Each case
requires exactly one matching request, the expected driver timeout and a saved
native-protocol request body. Reply loss additionally requires a real ROWS result,
a changed Cassandra guard and the exact expected full hot state after reconnect.
A RESULT opcode alone is insufficient evidence of acceptance.

Fresh SERIAL reads compare all commercial terms, receipts, slot rows, floor, seals
and fence. The before-send case must preserve the entire original state and guard.
An exact retry converges; another retry must preserve even the guard. Complete
archive coverage then supports pruning and a logical restore/recovery cycle;
accepted terms survive and retired IDs stay closed even after the controlled clock
moves backward. External archive and authority still remain in-memory fixtures.

The trace cut is assigned only after the wire witness is checked. Relabeling that
same observation with the opposite cut must fail the independent checker at the
faulted command. A separate broken-pruning control really advances the floor while
leaving receipt rows behind, loses its server reply and must fail at that exact
PRUNE state comparison. These cases are serial one-node observations, not a
concurrent ambiguity solver or a named internal Paxos-phase fault. The earlier
adapter's socket tests and the model's 16 synthetic cuts remain separate evidence.

## Evidence and next gates

Each scenario saves commands, outcomes, reloaded full state and checker verdict
to `build/evidence/archive-cassandra-single/` or `archive-cassandra-three/`.
Saved traces are deserialized and rechecked. Stale-write outcomes are separate
witnesses; process kills use the existing independent kill witness. The checker
must return VALID for correct runs and INVALID for broken implementations.
INCONCLUSIVE does not satisfy either gate.

The [signed-draft process fixture](signed-draft-process-recovery.md) now links
signed identities to this candidate with real child JVM restarts. Its draft bindings
are still in memory and orchestration is serial.

The [durable-server extension](durable-server-process-recovery.md) persists draft
bindings and external facts in one local atomic checkpoint. It tests real server
JVM exits, while retaining serial orchestration and a shared failure domain.

Next separate authority and archive failure domains, choose a remote-service contract, and
design a fence that survives the actual database disaster-recovery boundary.
Then test concurrent archive/restore operations, service outages and persistence,
whole-snapshot restoration, and controlled mid-Paxos faults. The authority still
retains unbounded metadata. PG-COMMIT and PG-CASS remain unproven.
