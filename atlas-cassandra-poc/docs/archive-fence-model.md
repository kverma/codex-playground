# Archive coverage bound to a recovery fence

AT-103–109 extend the bounded recovery experiments with a **test-only** three-store
model and independent checker. Execution status is recorded in the adversarial
review. No production adapter, provider or existing business semantics changes.

## Explicit storage steps

| Step | Store touched | Contract |
|---|---|---|
| START | Authority | Allocate a monotonically newer recovery owner; retain the earlier certificate. |
| FREEZE | Hot state | A newer owner closes authoring and advances the hot generation; capture the exact head, remaining rows, floor and archived-base certificate. |
| COPY | Archive | Reconstruct the captured prefix from its pinned immutable base plus captured hot rows; write a new immutable object for this owner. |
| VERIFY | Archive | Read the object and compare complete coverage, endpoint, owner and frozen generation with the capture. Retain the verified bytes as a local proof. |
| CERTIFY | Authority | Only the current owner may publish a certificate for its verified object. This uses the earlier proof, not an atomic archive/root operation. |
| PRUNE | Hot state | Require successful certification and the exact still-closed frozen generation; atomically advance the floor/base pointer and delete covered rows, advancing the generation. |
| INSTALL | Hot state | Require the exact post-prune generation and verified certified image; install with a new generation, never the generation carried by an old snapshot. |
| ACTIVATE | Hot state | Open authoring only after successful installation and only at that exact installed generation; advance the generation again. |

COPY/VERIFY use an actor's captured hot observation. CERTIFY/PRUNE/INSTALL/ACTIVATE
use earlier proofs and local CAS conditions. No step transacts across stores or
silently rereads current authority while mutating hot state. START is a recovery
request; FREEZE is the actual edit boundary. An older owner can finish while a
new owner has started but has not yet frozen hot state. Once the newer freeze
lands, the older hot-state transitions must reject.

The certificate binds owner, frozen generation, sequence, price and a SHA-256
digest of every ordered before/after receipt. This is **storage integrity metadata**,
not an Atlas intent ID or business-time revision. Numeric model owners/generations
are not proposed production token formats.

Archive objects are assumed immutable and retained after successful verification.
This is the essential external storage contract on which later proof reuse relies;
the model does not establish a remote provider's durability, prevent authority
rollback, or tolerate arbitrary deletion of certified archives. Faulty COPY inputs
model malformed artifacts **before verification**; they do not claim post-ACK
provider corruption has been handled. Objects rejected by VERIFY stay uncertified.

AT-109 deliberately **violates** this storage premise: remove the already verified
object, certify using the earlier proof, then prune with every candidate guard
enabled. The checker must reject the actual state at PRUNE because neither hot
rows nor the archive retain the accepted receipt. The prefix through certification
still retains hot data; the identical plan without object loss passes. This is an
expected environmental counterexample, not a passing disaster-recovery claim or a
real provider fault experiment. It demonstrates why a trustworthy durable/retained
archive ACK is required; more generation checks alone do not supply that guarantee.
This particular counterexample has no other durable receipt source. The separate
unbounded recovery-journal fixture retains before/after state and could provide
additional recovery data; it is not included in this model. A real integration
must account for those copies and their retirement policy before claiming either
safe deletion or unrecoverable loss. No data-loss bug in that existing Cassandra
journal is inferred from AT-109.

## Independent evidence

The candidate reconstructs a cut from its captured base and rows. The checker
instead maintains its own ledger of accepted edits and freezes that ledger at
the observed cut. It does not call candidate transitions, reconstruction or hashing.
After every command it compares complete root, hot state and archive objects.
It additionally reconstructs the full accepted history from the actual certified
base plus the actual remaining hot rows. A correct final price alone cannot hide
a dropped receipt, incorrect floor, revived generation or changed certificate.

- All `12! / (8! 2! 2!) = 2970` order-preserving merges of one eight-step recovery
  and two read/write clients.
- All `16! / (8! 8!) = 12870` merges of two recoveries after one accepted edit.
  This second profile has no writer interleaved during the two handoffs; it is
  explicitly not enumeration of two recoveries plus concurrent writers.
- A separate 22-step trace prunes a prefix, rejects a delayed writer after
  installation, accepts a fresh tail edit, then copies/prunes/installs both exact
  receipts through a second recovery.
- Three malformed copies: omitted receipt tail, wrong fence and wrong endpoint.
  Each blocks verification/certification/cleanup/activation, retaining hot rows;
  a newer owner with a valid copy resumes authoring with the edit intact.
- Seven actual broken candidates: accept bad coverage; certify a superseded owner;
  prune without certification; prune across a newer fence; install an older image;
  restore an old generation; activate without installation. Each asserts its exact
  unsafe boundary, is saved/replayed as INVALID, and has a passing same-plan control.
- Every whole-execution prefix is checked for safety. Full schedules separately
  require restored authoring progress. The checker returns INCONCLUSIVE above its
  24-step bound; an explicit seven-step test cannot approve an eight-step plan.

Evidence lives in `build/evidence/archive-fence/`: full schedule traces, census
summaries, malformed-copy recovery traces, the prefix/tail trace and seven pairs
of unsafe/control traces. Schedule counts never inflate reported JUnit test counts.

## Still outside this model

There are no network threads, storage processes, actor restarts, journal allocation,
lost replies, reservations/gaps, supported archive deletion or bounded journal retirement.
The separately tested exact-operation journal and real Cassandra adapters are not
integrated here. INSTALL is a logical hot-state replacement, not SSTable restore.
The result narrows the coordinator design but does not supply an authenticated,
persistent multi-service implementation. PG-COMMIT/PG-CASS and production E2E remain
UNPROVEN; the [Actions-only boundary](actions-only-boundary.md) still applies to
those larger claims while this additional bounded work remains executable.
