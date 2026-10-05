# Server-process checkpoint contract

Status: implemented for hosted validation; no execution result recorded yet.
Run through `make grade-cassandra` (Gradle, JDK25, Cassandra4.0.5).
AT-080 and AT-081 describe the boundaries in end-user terms.

## What survives a server process

Each request launches a new server JVM. It reads a versioned checkpoint before
opening Cassandra, executes one fixture request, publishes changed external facts,
and returns a result file. There is no parent-held authority injected on restart.
The parent supplies only the request and filenames. Cassandra retains the actual
HEAD and slot rows; the checkpoint contains genesis, time, reservation high-water,
certified floor, grants, draft-to-Issued bindings, manifests, archive objects and
logical backups. Hot state is always read from Cassandra, not the checkpoint.

This is a **single-writer local filesystem fixture**. Binding and authority share
one atomic publication unit. It intentionally does not implement the more difficult
production case where authority and archive are independently failing services.
The original in-memory fixtures and RetentionCassandraStore remain separate.

## Publication and acknowledgement

1. Serialize the complete checkpoint inside a versioned, SHA-256 checked envelope.
2. Write and force a sibling `.pending` file.
3. Atomically replace the current checkpoint, then force its parent directory.
4. Only then return success. Unsupported filesystem operations fail the request.

An unpublished `.pending` file is never promoted on restart. A missing, malformed,
checksum-invalid or internally unbound checkpoint fails before Cassandra opens;
it never creates an empty authority for an existing subject. The checksum detects
accidental byte damage, not an adversary able to rewrite the checksum. Sequence,
grant and binding consistency checks additionally reject a correctly checksummed
checkpoint with a missing binding.

Reservation and signed binding are published **together before INSTALL**. A crash
before publication leaves neither durable fact; a crash after publication leaves
both. Retry installs the existing reservation. Acceptance and pruning use the
existing same-partition Cassandra conditional batch. A crash after the database
change is resolved by reading its actual state on the next request.

For COPY, success means complete checkpoint bytes have reached the force/rename
boundary. A deliberately PARTIAL blob may be stored by the fault fixture but is
never sufficient for certification or deletion. Every PRUNE rechecks archive
coverage even after a certified-floor checkpoint exists. Corrupt content blocks
recovery and leaves a logically restored offer fenced.

## Evidence and adversarial controls

`build/evidence/durable-server/` retains every request, child PID, exit status,
server log, before/after checkpoint bytes, reply or cut witness and full histories.
A requested cut must end with exit 86 and a matching saved boundary witness;
an unrelated crash is a test failure. `Runtime.halt` bypasses normal JVM shutdown.
The witness is test evidence and is never read by a recovering server.

The positive trace includes only published external transitions and actual database
transitions. The two pre-publication attempts are retained as explicit staged
witnesses with unchanged authoritative checkpoint bytes; they are not represented
as durable RESERVE/COPY events. The independent ArchiveRecoveryChecker validates
and replays the resulting serial history. Removing its reservation is a negative
control that must fail at installation. This does not turn the serial checker into
a concurrent or combined cryptographic protocol oracle.

Other cases save a partial archive, physically remove/truncate/damage the checkpoint,
remove a binding while recomputing its envelope, and change archived grant content.
Tests require preserved Cassandra state or fenced recovery, not merely an exception.
The test restores trusted bytes between corruption cases; the candidate has no
fallback to an older file. Existing signed-process tests separately validate signed
retry histories and changed-content controls.

## Remaining boundaries

These tests qualify process exit and restart on a hosted Linux filesystem. They do
not simulate loss of kernel page cache, power, disk, runner or region. Directory
force is performed but its hardware durability is not established. There is no
remote archive provider, independent authority failure, concurrent writer protocol,
rollback-resistant external root, binding garbage collection, key rotation or real
whole-cluster backup restore. An older otherwise valid checkpoint may pass parsing;
preventing rollback requires a separate trustworthy recovery authority. The fixture
assumes one writer per checkpoint; it is not a distributed lock or fencing service.
Atlas PG-COMMIT, PG-CASS and nine-node HA remain unproven.
