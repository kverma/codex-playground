# Separately read archive and authority facts

[Run 37323949963](https://github.com/kverma/codex-playground/actions/runs/37323949963)
passed all four jobs at `223b9963a381886a56772c6e20e2c9d512c38de8`:
**112 distinct POC cases +16 upstream**, zero failures/errors/skips. All four
artifacts were downloaded and inspected. The new slice used 53 distinct server
JVMs: 19 publication, 19 independent-read and 15 root-rollback processes. Five
halts and seven exact read rejections were witnessed. Positive histories are VALID
(15 and 12 commands); the rollback control is INVALID at RECOVER (zero-based index 11).
Offline inspection verified every saved root reference against the actual file
SHA-256, unchanged roots at staged cuts/read failures, fault identities, process
exits and actual reuse of the retired operation with a different receipt.

AT-082–084 run through the existing
`make grade-cassandra` / Gradle integration task with JDK25 and Cassandra4.0.5.

## Contract

The server-process fixture now has an optional split-storage mode. Authority facts
(grants, draft bindings, manifests, certified floor and logical backups) and archive
objects occupy separate directories and immutable content-addressed files. A small
root contains the subject and exact SHA-256 identities of both files. The authority
file cannot hide a second copy of the archive. On each new server JVM, both exact
files must be readable and match the root before Cassandra is opened. No directory
scan, stale fallback or parent-owned map repairs a failed read.

Publication writes/forces archive bytes first, authority bytes second, then atomically
replaces/forces the root. Files not referenced by the root are uncommitted orphans;
restart ignores them. A crash before root publication cannot promote them. A crash
after publication can recover the same signed identity or archived receipt. This is
a serial single-writer protocol; it has no multi-writer root CAS or distributed lock.

The root is the publication boundary and **its freshness is assumed, not proved**.
Separating files permits independently injected read and replacement faults; they
still live on one filesystem and are served by one fixture process. This is not a
remote object service, independent service process deployment or separate hardware
failure domain. Missing files and paths replaced by directories are real filesystem
errors, not simulated network outages. Their safety result must not be cited as a
remote provider's availability or durability qualification.

## Required evidence

- Halt after staged authority, published binding root, staged archive, published
  archive root and published certificate root. Require exit86 and an exact saved
  phase witness, unchanged root bytes before publication, and actual orphan files.
- Independently remove, obstruct or substitute valid older authority/archive bytes.
  Require the exact missing/read/digest failure and unchanged Cassandra state/root.
  Restore bytes in the test, then require successful cleanup and exact recovery.
- Remove archive bytes after logical restore. Recovery must remain fenced until the
  exact referenced bytes return; there is no automatic fallback to an older root.
- Deliberately roll back the supposedly trusted root. All old files remain valid,
  so the loader accepts them. The test must demonstrate actual unsafe recovery and
  reuse of the retired operation identity. The independent archive oracle must
  reject the resulting history at RECOVER. This is a negative control documenting
  an unresolved production requirement, not a passing rollback-protection feature.

Requests, roots before/after each process, immutable files, crash/read witnesses,
PIDs, logs and checked histories are under `build/evidence/split-storage/`.
Successful scenarios require VALID with deserialized replay. The root-rollback
control requires INVALID at the specific recovery command and evidence that the
retired identity really reopens. No generic exception counts as that control.

## Next boundary

Choose and validate an independently hosted, rollback-resistant authority/root
contract with its own quorum/fencing and disaster recovery semantics. Then qualify
remote archive read/write ambiguity, independent service restarts, concurrent root
updates and whole-host loss. Object/binding retention and orphan garbage collection also
remain unbounded in this fixture. Content addressing proves which bytes were returned;
it cannot establish that a returned root is the most recent authorized one.
Nine-node RF3/DC and canonical Atlas proof gates remain unproven.
