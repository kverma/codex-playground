# Atlas batch and repair overlay on Cassandra 4.0.5

`maintainer/AtlasBatchPhaseTest.java` is Atlas-authored test code copied into a
fresh checkout of upstream commit `ec476e0e259efb62ee19804c3ff46dbbe4d1ded7`.
It runs on the separate JDK11/Ant harness, alongside—not counted as—the sixteen
unmodified upstream smoke tests. Server source and JVM durability flags are not
changed. The overlay source is uploaded with its evidence.

## Conditional batch phase cuts

Two cases each enumerate six selected message boundaries: prepare, propose and
commit, request and response. Each uses the pinned message-filter API to drop
remote messages between coordinator 1 and replicas 2/3. A counter must prove that
the selected verb/direction was intercepted, and the operation must report the
specific Cassandra CAS timeout. Unexpected errors or unhit filters fail.

The HEAD/receipt batch mirrors `CassandraStore`'s schema and two conditions. The
floor/delete batch mirrors `ArchiveCassandraFixture`'s same-partition conditional
HEAD replacement plus row deletion. The latter uses fixed representative metadata
strings; this exercises the storage mutation shape, not the complete archive
semantic model or driver adapter. Both before/after expected row sets are explicit.

After filters are removed, a SERIAL read on coordinator 2 resolves the uncertain
operation. Only a complete before or complete after partition is allowed. All
three coordinators must agree; exact reissue either applies once or rejects an
already-applied old condition, and all final row sets must match the complete after
image. No timeout is labeled an aborted write. `ATLAS_PHASE` output retains kind,
verb, hit count, exact error and resolved/final rows.

An actual broken-prune control executes only the HEAD update. The independently
specified row-set oracle must reject the observed partial partition while the
undeleted slot is still present. This is not a fabricated trace or mocked failure.

## Witnessed repair overlap

A separate three-node, real-network in-JVM cluster has hints disabled and table
read repair set to NONE. One sentinel partition exists only on replicas 1/2 and
is verified absent locally on replica 3 before full repair. All replicas flush.

The harness pauses one actual VALIDATION_RSP from replica 2 to coordinator 1.
While this response is held and repair is pending, a HEAD/receipt batch must
complete through coordinator 3. Monotonic timestamps require
`pauseStart < writeStart < writeEnd < pauseEnd`; missing phase/timeout/no overlap
is a failure. The response is delivered after release, not dropped.

Nodetool must succeed and the parent-repair history must show successful completion.
Direct local reads on all three replicas verify sentinel healing before any
coordinator read of that sentinel. SERIAL reads verify the exact edited partition
after repair. `ATLAS_REPAIR_PAUSE` records the measured interval and controls.

This witnesses a write while a repair validation result is pending. It does not
claim that an edit overlapped a particular disk streaming callback or validation
hash computation. The harness skips disk sync and uses classloader-isolated nodes;
none of these cases qualifies power-loss durability, independent hosts, real WANs,
RF3/DC, a production archive provider or Atlas end-to-end correctness.
