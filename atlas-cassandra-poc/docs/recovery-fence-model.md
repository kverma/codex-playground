# Bounded recovery-fence handoff

Status: implemented; GitHub validation pending. AT-091–094 run in `make grade-model`
and the existing hosted model/single-node job. These are test-only models, not a
change to the Cassandra archive/retention adapters or a new production protocol.

## Why this slice exists

A recovery-root CAS does not by itself fence a provider edit already on its way to
the offer partition. A delayed writer can hold an older authoritative observation.
The offer mutation itself must enforce a fresh local generation and authoring gate.
The existing serial archive fixture cannot prove that concurrent boundary.

The candidate separates each recovery into four atomic effects, each touching only
one modeled store. No step silently transacts across root and offer partitions.

| Step | Store | Required behavior |
|---|---|---|
| START | Authority | Allocate a newer recovery owner; keep the previous checkpoint. This requests recovery but does not yet close hot-state authoring. |
| FREEZE | Offer | Install a newer recovery epoch, close authoring and change the hot generation atomically. Return the complete price and receipt chain at this cut. |
| PUBLISH | Authority | Publish the immutable captured checkpoint only while the same recovery owner is current. Never reread mutable hot state inside this update. |
| ACTIVATE | Offer | With a successful publication proof, reopen only the exact frozen generation and change the generation again. Never check authority and hot state as one atomic operation. |

The freeze, not START, is the effective write boundary. An already-prepared edit
can still commit between START and FREEZE; its receipt must enter the captured
checkpoint. After FREEZE, stale edits cannot commit, including after ACTIVATE.
A newer START may race an older ACTIVATE before the newer FREEZE: the old activation
can legally occur in that draining window. The later freeze must capture any
intervening accepted edits. A stronger immediate barrier at START is not claimed.

Owner/epoch/generation numbers are deterministic model identities. They do not
order production UUIDs, replace semantic hashes or represent business effective
time. The commercial state is deliberately just one price and its exact ordered
before/after receipts. Group validation, leases and operation allocation are absent.

## Exhaustive within the stated bound

- One recovery (four steps) plus two writers (read, then conditional write):
  `8! / (4! 2! 2!) = 420` schedules.
- Two recoveries plus one writer: `10! / (4! 4! 2!) = 3150` schedules.
- Each actor's own order is preserved; all merges of those sequences are visited.
- Every whole-execution prefix is checked for safety. A fenced prefix is not
  completion evidence; progress is required only after the uninterrupted schedule.

This is deterministic exploration of modeled atomic storage effects, not concurrent
threads, measured overlap, arbitrary Paxos phases, or exhaustive real-world proof.
The default checker bound is sixteen steps. INCONCLUSIVE is never VALID.

## Independent checker and adversarial controls

`RecoveryFenceChecker` owns its own writer observations, recovery ownership,
freeze cuts and receipt ledger. It does not call the model's transitions or read
its private caches. It derives expected outcomes and compares full authority and
hot-state observations after every step. The two share only immutable trace records
and the small documented price/operation format, not a transition implementation.

Five actually executed broken variants must produce INVALID traces: write while
frozen; ignore the writer's generation; let an old owner overwrite the root; let
an old recovery reopen/replace newer hot state; omit accepted receipts from the
published checkpoint. Each captured schedule is rerun against the correct model
and must pass. Saved JSON is reloaded and checked again. Counterexamples are complete
bounded traces; no minimality claim is made.

Each saved counterexample also asserts its named failure boundary: a closed gate
for WRITE_WHILE_FROZEN, an open gate with an outdated read generation for STALE_WRITE,
a superseded owner for STALE_ROOT, a changed hot generation for STALE_ACTIVATE,
and a nonempty captured receipt chain omitted by DROP_TAIL. This prevents two
different mutant names from merely demonstrating the same stale-write condition.

Evidence: `build/evidence/recovery-fence/` contains all 3570 full traces, two census
summaries and five rejected/replayed mutant traces. The four JUnit cases remain
four tests in reported counts; schedule/prefix counts do not inflate that number.

## Still open

No archive copying/pruning, SSTable/whole-database restore, restored allocator,
lost request/reply, actor restart, journal durability, service outage or rollback
is modeled here. In particular, hot state must not be physically restored in a way
that revives an old authoring generation. Numeric ownership is not an independently
durable production recovery authority. Root-only Cassandra results remain separate.

Next extend the handoff to interrupted actors and exact-operation recovery, then
bind archive coverage and installation to the fenced generation, with independent
checks for old snapshots and missing/unarchived tails. Only after those contracts
are concrete should the two-store Cassandra adapter integrate this candidate.
PG-COMMIT, PG-CASS, RF3/DC and production certification remain UNPROVEN.
