# Archive and restore: bounded model specification

## Status and scope

This is a separate serial crash-boundary candidate in `ArchiveRecovery`, exercised
by `ArchiveRecoveryTest` and `ArchiveRecoveryChecker`. It does not change
`RetentionCassandraStore`: that earlier adapter still prunes without an audit
archive. No remote archive, production recovery authority, distributed fencing
implementation, or Cassandra archive transaction is supplied by this model.

Use the existing `make grade-model` gate on JDK25/Gradle9.1.0. A successful model
run qualifies the transitions and injected faults below, not a storage service's
durability or a distributed protocol. Cassandra remains pinned to 4.0.5 for the
existing regression jobs.

## Durable domains and explicit assumptions

| Domain | Modeled facts | Restore rule |
|---|---|---|
| Hot subject partition | Commercial head, allocated position, expiry floor, request/receipt rows and sealed-row markers | An old saved snapshot can replace all of these facts. It cannot enable authoring by itself. |
| Recovery authority | Monotonic allocation high-water mark, immutable reservation bindings, sealed-record digests and certified archive floor | Survives hot snapshot restoration. If unavailable or inconsistent, recovery stays fenced. |
| Archive | Complete immutable slot records, containing either the exact accepted receipt or CLOSED_UNACCEPTED | Separate from hot state. Partial objects and volatile RPC acknowledgements are not durable coverage. |
| Trusted genesis | Original commercial snapshot and subject context | Anchors reconstruction; cannot be substituted by the restored snapshot. |
| Recovery fence | Revocation/drain of old writers and pending writes before restored state can serve mutations | A required external barrier, modeled as a control transition. Actual enforcement across DCs is unproven. |

All three storage domains are in-memory model fixtures. Their persistence across
the modeled crash/restore actions is an assumption, not a measured disk or remote
archive property. The recovery authority intentionally retains all reservation and
manifest facts; its production representation, availability, compaction and disaster
recovery remain open. This does not solve bounded metadata storage by renaming it.

The model consumes reservation grants for a fixed sequence and request. It does
not implement the signed-draft allocator or SDK; those must be integrated and
validated separately. The authority rejects changed grants or operation-ID reuse.
One reservation write precedes hot installation, so a crash can leave a known
allocation gap. That gap cannot be ignored during recovery.

## Ordered transitions

Each row is a separate operation. There is no transaction spanning the authority,
hot partition and archive. BEFORE/AFTER faults represent no-effect loss and effect
with a lost reply at these boundaries. Exact retries must remain safe.

| Step | Preconditions and effect |
|---|---|
| RESERVE | Active authoring and available authority. Persist the next sequence/request binding and raise the authority high-water mark. An identical retry returns the original binding. |
| INSTALL | The durable reservation exists; install the next contiguous hot row. The commercial head does not change. A reservation without installation remains an unresolved recovery gap. |
| ACCEPT | An installed, unexpired, unsealed row can accept under the existing commercial dependency rules. Head and receipt change together in the model. Retained acceptance replays its exact receipt; a retired row cannot accept again. |
| SEAL | Close an expired contiguous prefix through a hot-state transition. Accepted receipts remain immutable; unresolved rows become CLOSED_UNACCEPTED. No later acceptance may enter this prefix. |
| PUBLISH | Copy a sealed slot's digest to the recovery authority. Publishing a different record for the same slot is a contradiction. This requires a trustworthy sealed-state read in the eventual adapter. |
| COPY | Transfer that sealed record to the archive. Partial data or a volatile acknowledgement can return RPC success but cannot certify coverage. A durable write followed by reply loss is recovered by exact retry. |
| CERTIFY | Independently verify complete archive records against reservation bindings and sealed digests for a contiguous prefix, then advance the authority's certified floor. |
| PRUNE | Require certified coverage, available verification and locally sealed rows. Advance the hot floor and delete that prefix together. Preserve the commercial head. An uncertain outcome permits a coherent old or new state, never a split floor/delete state. |
| SAVE | Capture a hot snapshot as a named restore candidate. It is not a new trusted genesis. |
| CRASH / RESTORE | Fence authoring. A crash preserves modeled durable domains and discards volatile acknowledgement state; restore additionally loads an earlier hot snapshot. Neither rolls back the recovery authority or archive. |
| RECOVER | Require available authority/archive, certified floor equal to the allocation high-water mark, and every slot's complete verified record. Reconstruct the accepted receipt chain from genesis; install its final head, close all recovered IDs, then enable authoring. Any missing tail, corruption or contradiction leaves authoring fenced. |

Sealing precedes publication: archiving an unresolved row while it could still
accept would allow the archive to lie about the outcome. The eventual Cassandra
adapter must couple sealing with the same mutation guard as acceptance, and prove
that delayed requests cannot cross that guard.

Recovery is deliberately conservative. Even a reservation that was never installed
blocks recovery if it lacks a verified closed record. Likewise an acknowledged but
unarchived acceptance prevents restoration from an older certified prefix. The
model does not guess whether either operation happened. Resolving such gaps from
surviving authoritative state or a separately proven cancellation/fencing protocol
is future work; permanent loss of those facts can mean permanent unavailability.

## Reconstruction and intent example

Receipt order follows the before/after state chain, not allocation sequence.
For example, Royalty operation 2 can commit before independent Economics operation
1. Recovery must reproduce the resulting head, rather than sort receipts by ID.
Forked or incomplete chains cannot authorize recovery.

A January edit sets price to 400 and targets NEW customers; a February edit expands
eligibility to NEW and CHURNED. After pruning and restoring an old snapshot, the
archive must retain both exact before/after receipts and recover the February head.
Advancing the clock alone does not change the semantic intent hash.

January/February are illustrative labels in this bounded fixture: business effective
periods and downstream event metadata are not yet fields in its commercial schema.
Production audit reconstruction must preserve those fields as well as the receipt
content demonstrated here. Audit access is separate from permission to retry a
retired mutation; archived receipts do not reopen old tickets.

## Independent checks and evidence

The checker retains full sealed records in a separate specification state and checks
every observed transition. It does not call the model's transitions, digest function,
coverage predicate or receipt-chain walker. It validates commercial acceptance with
the existing independent `HistoryChecker` and derives the restored head from that
validated acceptance history. Restoring a backup never resets the oracle's history.
Request hashing and record/codec definitions remain shared, retaining R13's limits.

This is a sequential transition checker with explicit crash cut points, not a search
over concurrent histories. A trace above 512 steps, or one exceeding the nested
commercial oracle's bounds, is INCONCLUSIVE and cannot pass workload grading.
Generated traces also require successful certification, pruning, recovery, exact
final state and closed old IDs. Merely remaining fenced is safe but cannot satisfy
their progress gate. Tests for unavailable/corrupt recovery facts intentionally
require continued fencing instead.

Coverage includes 100 generated traces, 16 before/after boundary scenarios, stale
snapshot restoration, allocation gaps, unarchived accepted tails, backward clocks,
lost replies, partial/volatile writes, unavailable domains, corrupt/missing objects,
and a fresh model facade over surviving modeled storage after a crash.

Nine deliberately broken implementations must produce INVALID traces: pruning
without coverage, trusting a volatile ACK, trusting a partial object, split
floor/deletion, activating an old snapshot, rewinding authority facts, resurrecting
a closed row, ignoring archive corruption, and recovering while omitting a newer
uncertified tail. These change real model state/decisions; counterexamples are not
fabricated by editing trace JSON. Each saved trace is deserialized and checked again.

Evidence is saved under `build/evidence/archive-generated/`, `archive-boundaries/`,
`archive-contract/` and `archive-mutants/`, including genesis, commands, declared
fault cuts, outcomes, full observations and verdict. The complete bounded traces
are replayable; no minimal-counterexample claim is made.

## Gate after this model

After the model gate passes, implement a separately gated Cassandra adapter test for
the seal/publish/certify/prune ordering and restoration fence. Choose a concrete
archive/recovery-authority contract before claiming remote durability. Demonstrate
that authority and archive survive the actual recovery boundary, and that every
writer—including delayed requests and old deployments—is fenced. Retain the
independent checker and all negative controls. PG-COMMIT and PG-CASS remain unproven.
