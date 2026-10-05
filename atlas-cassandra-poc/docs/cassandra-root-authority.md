# Authoritative root outside logical offer restoration

Status: implemented; hosted validation pending. AT-085 runs via
`make grade-cassandra`; AT-086 runs on one node and via dc1/dc2 clients in
`make grade-archive`. JDK25, Gradle and Cassandra4.0.5 remain unchanged.

## New boundary

A separate `atlas_poc.recovery_root` table stores subject, root payload and a fresh
UUID guard. Explicit test bootstrap is conditional (`IF NOT EXISTS`). Server reads
use SERIAL; publication uses `IF guard=?` with SERIAL/QUORUM and no custom timestamps.
A missing authoritative row is never bootstrapped from a local cache during recovery.
The local root file is only a cache. Offer restore changes the hot HEAD/slot rows,
not this root table. Archive/authority payload files remain content-addressed.

A new server process reads the root from Cassandra, validates the exact referenced
payload files, then opens the offer fixture. Publication stages immutable files,
conditionally publishes the root, then updates the cache. A halt between those last
two steps leaves a stale cache; restart recovers using the authoritative root.
Old local files cannot choose an older authority generation. Read ambiguity is an
unknown outcome, not permission to fall back to a cache.

## Required evidence

AT-085 halts real JVMs after binding-root and certification-root CAS, before cache
replacement. The old cache bytes must remain unchanged while a fresh SERIAL read
observes the new authoritative root. Retrying must return the same Issued identity.
After acceptance, archive certification and pruning, the test logically restores
old offer rows, substitutes an old cache and recovers the exact receipt's final
state. Backward time cannot reopen the original draft/ticket. Deleting the cache
also cannot rewind authority or bootstrap an existing offer.

AT-086 captures a root guard, changes its content, returns to the original content,
and delivers the captured old update. That actual conditional write must report
not-applied; a fresh read must match the full current guard and payload. The shared
case uses separate clients on one node and actual dc1/dc2 coordinators on three
nodes. It checks stale publication, not a concurrent authoring protocol. Placeholder
content hashes in this isolated CAS case are not evidence of archive object validity.

The earlier split-file rollback negative control remains: if the authoritative root
itself is rolled back along with its recovery facts, old identities can reopen.
The new result only removes local-cache rollback from that trust boundary.

## Explicit limits

Root and offer rows are separate partitions; no atomic transaction spans them.
Orchestration remains serial. A root CAS that fails after a hot-state change is not
a general concurrent-writer recovery protocol. The test does not claim root guards
fence hot-state writers, nor does it qualify a chosen Paxos phase or lost CQL reply.
The halt occurs after a witnessed successful root update.

The root is in the same Cassandra cluster, on one hosted runner. Logical offer
restoration leaves it intact by construction. Whole-cluster restore, stale authority
backups, replica rollback, independent hosts, root-service unavailability and root
retention/garbage collection are not qualified. In particular this is **not** a
rollback-resistant production root service. A production recovery authority needs
its own disaster-recovery contract and failure domain. Nine-node HA, PG-COMMIT and
PG-CASS remain open.
