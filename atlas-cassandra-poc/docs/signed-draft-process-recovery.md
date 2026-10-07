# Signed drafts across client-process restart and archive recovery

## Verified hosted result

[Run 37298208324](https://github.com/kverma/codex-playground/actions/runs/37298208324) passed at
`521bc1843ae54073ceef8769e97a3f17f5ee2784`: **107 distinct POC cases + 16 upstream**.
The new scenarios used 39 distinct client JVMs: 14 for allocation-reply loss, 14 for
acceptance-reply loss and 11 for stale/tampered files. All three signed prefixes
are LINEARIZABLE, all three changed-content controls are NON_LINEARIZABLE, and
all three full archive histories are VALID. Saved journals, wire results and
process identities were inspected; hashes and HMACs were independently recomputed.

The separate [durable-server extension](durable-server-process-recovery.md) adds
local disk checkpoints and real server exits. The limits below describe AT-078–079,
whose parent-JVM fixture remains unchanged.

## Contract in this slice

A restarted client loads its original signed draft and issued request/ticket from
a local journal. It must not refresh dependencies, silently create a new draft,
change payloads or mistake a lost response for a rejected edit. After retirement
and logical archive recovery, old identities stay closed even if the controlled
clock moves backward.

AT-078–079 run with `make grade-cassandra` and the single-node `make grade` job.
They launch real, separate JDK25 client JVMs. Each child reads its journal, sends
one request and exits; the next child gets no in-memory client state from it.
The fixture uses a loopback length-prefixed transport with no automatic retries.
It is not the production Atlas HTTP API.

## Composition and limits

`SignedArchiveService` is a serial test adapter. It reuses the existing draft and
ticket signature helpers from `Retention.Base`, whose encodings and normal store
behavior are unchanged. Allocation reserves an archive grant, remembers the draft
binding and installs the slot. Acceptance uses the archive candidate's conditional
Cassandra mutation. The full original request accompanies the issued ticket.

The draft-binding map, recovery authority and archive remain in the parent JVM.
Binding records are retained indefinitely; bounded metadata compaction is unproven.
They survive child-client exits but are **not durable across server/host loss**.
Reserve/bind/install are serial fixture steps, not a cross-store atomic protocol.
The tests do not inject a crash between those steps. RetentionCassandraStore is
unchanged; this is a separate bridge to the archive fixture, not a production
migration or a distributed allocator implementation.

The journal has a version, subject UUID, complete signed Draft and optional Issued
pair. Writes force a temporary file and atomically rename it on the same filesystem.
There is no directory-fsync, power-loss or torn-write qualification. The child exits
after recording its outcome; this is not a SIGKILL-during-file-write experiment.
Malformed files and unsupported versions fail locally before any server request.
Only synthetic fixture data is used. No signing key is sent in journals or over
the transport. The synthetic key is present in shared test code; this harness does
not test secret isolation.

## Required observations

| Boundary | What must be proved |
|---|---|
| Allocation reply lost | Server really reserved and installed one slot; it records the exact successful reply before closing the connection. The journal remains unchanged, and a new client recovers that same Issued identity. |
| Acceptance reply lost | Server really stored the receipt; the lost reply matches it. A new client recovers the exact receipt without modifying the journal. |
| Later independent edit | Retrying the old edit returns its original receipt and does not revert newer terms. |
| Logical restore | Certified archive coverage precedes pruning. Restored state blocks authoring until recovery; old draft/ticket stay closed afterward, including after a backward clock change. |
| Fresh edit after recovery | A new draft receives the next sequence and can update terms without reopening retired IDs. |
| Stale saved dependencies | A conflicting intervening edit makes the saved draft conflict; allocation preserves its original dependencies. |
| Tampering | Changed draft or issued payload and a changed subject fail without an accepted edit or silent reminting. |
| Invalid local journal | Malformed JSON or unknown schema version causes no server request. |

Each child result records its actual PID and exit code. The tests require different
client processes, the expected transport-unknown exit for reply loss, exactly one
matching dropped reply per loss scenario, and full request/response equality.
A generic server exception or an unrelated dropped connection cannot replace that
witness.

## Independent checks and evidence

The independent `RetentionChecker` validates the serial signed allocation/acceptance
prefix, including signatures and original read dependencies. A deliberately changed
Issued request with an unchanged ticket must be NON_LINEARIZABLE. The independent
`ArchiveRecoveryChecker` checks the full grant/accept/seal/copy/certify/prune/restore
history against reloaded Cassandra state. These are separate checks: neither claims
a combined concurrent cross-service proof. Client lost replies are recorded in the
wire trace; the archive trace records the server's known committed outcome.

Evidence is under `build/evidence/signed-process/{issue,commit,stale}/`: persisted
journals, process results/logs, exact server messages/replies, the checked signed
history and its rejected mutant. Full archive histories and replay results are under
`build/evidence/archive-cassandra-single/signed-process-*`.

Beyond the local checkpoint extension, still open: independently durable authority
and archive services, concurrent allocator/archive operations, partial client journal-write crashes,
key rotation, independent hosts, production transport and actual whole-database
restore. Canonical Atlas proof gates remain unproven.
