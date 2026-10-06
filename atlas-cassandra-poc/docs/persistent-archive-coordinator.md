# Persistent archive coordinator fixture

AT-110–117 connect the bounded archive phase contract to separate real Cassandra
authority/hot journal partitions and local immutable archive files. Each actor has
a forced, atomically replaced local checkpoint containing captured state, verified
archive bytes, exact pending operation/expected/next guards, and original receipts.
The candidate plans transitions from those persisted facts, not from the model.
Execution status belongs in the adversarial review; adding these tests is not a
passing validation claim.

The actor checkpoint is published before dispatch. After a worker disappears,
the next worker reuses its exact pending request. If its effect already happened,
the journal returns the original receipt. Captured proof comes from that receipt's
original transition, never a later head. Only then is local phase completion
published. A worker resuming after that local publication starts the next phase.
Unknown driver outcomes leave the pending bytes in place; no fresh operation ID
or guard is minted to make an uncertain or conflicting phase succeed.

Each mutation remains one same-table Cassandra partition conditional batch with
immutable operation receipt. There is no transaction spanning the authority,
hot state, actor checkpoint and archive. Per-actor file locks serialize only that
actor's checkpoint, not independent owners or Cassandra partitions. Archive files
are forced then published with an atomic hard link that cannot overwrite an
existing different object. Their containing directory is forced too. The local
Linux filesystem is a test backend, not an independent durability service.

Before certification, cleanup, installation or activation the fixture checks that
the verified archive remains readable and byte-equivalent. Observed missing or
corrupt files block progress without changing the saved request. This read does
not close the cross-store race against subsequent storage loss: retained immutable
archive durability remains an explicit assumption. The actor proof and unbounded
journal also retain recovery copies, so loss of one archive is not automatically
loss of the last durable edit.

## Test boundaries

- Single-node and cross-DC clients: two handoffs with an archived prefix and a new
  hot edit; persisted CERTIFY/PRUNE/INSTALL/ACTIVATE requests after takeover;
  missing/corrupt actor and archive files; competing owner starts; historical
  phase receipts after a newer owner finishes.
- Actual unsafe control: replace an old prune request's expected guard with the
  current one. Its old image really overwrites Cassandra. The independent ledger
  rejects that boundary, while the exact-original-request control conflicts.
- Twenty-four real process halts: all eight phases after pending persistence,
  after storage effect, and after local completion. Forty-eight fresh JVMs record
  the interrupted/resumed boundaries.
- Twelve actual wire cuts: START/FREEZE/CERTIFY/PRUNE/INSTALL/ACTIVATE before send
  and after a server reply. Twenty-four fresh JVMs recover the exact saved request.
- Every successful history has full root/hot/archive frames checked against the
  independent archive ledger. `scripts/audit-archive-phases.py` separately checks
  those frames, complete journal chains, actor proofs, mutation rejection,
  original operation identities, cut witnesses and process IDs.

`make grade-recovery-phase` requires the single-node fixture and runs in a separate
25-minute standard Actions job. Cross-DC contract tests join `make grade-archive`.
The separate job prevents new process tests from using the older fault suite's
timeout budget. Shallow validation still compiles all integration sources first.

## Limits and next decisions

This is a bounded, trusted fixture, not an authenticated multi-service runtime.
The actor checkpoint must be retained and must not be rolled back; a missing or
corrupt checkpoint is not silently regenerated. There is no automatic ownership
retry loop after a definitive conflict. Journal receipts and old archive objects
are unbounded, and storage snapshots, host power loss, independent failure domains,
RF3/DC, reservation gaps and production workload capacity are outside this fixture.

Safe journal retirement requires an explicit supported retry horizon and a durable
record that prevents expired phase requests from becoming new operations. No such
production retirement policy is selected here. Archive/authority provider ACK and
rollback qualification likewise require backend contracts and appropriate failure
infrastructure. PG-COMMIT, PG-CASS and production E2E remain UNPROVEN.
