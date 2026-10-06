# Authority read and publication wire faults

Status: passed in hosted [run 37403100828](https://github.com/kverma/codex-playground/actions/runs/37403100828) at `86c1288`; downloaded protocol/process/history artifacts inspected. AT-087–088 run through
`make grade-faults` in the existing one-node job, with JDK25 and Cassandra4.0.5.
The three-node root CAS contract remains a separate gate.

## What actually fails

The native-protocol v4 proxy can now target a specifically marked QUERY as well
as the existing batch targets. Root reads and root conditional updates carry
subject-specific READ/CAS markers. Constructor queries and offer-state access
remain reachable. Each fault must intercept exactly one target query; an unrelated
exception, an automatic retry or a bypassed target is not a successful test.

For reads, the proxy drops the SELECT before forwarding, or discards its real
server RESULT. A valid older cache is present. A new server process must exit75
with AUTHORITY_UNKNOWN and DriverTimeoutException. Actual offer state, cache bytes
and authoritative root must remain unchanged. A subsequent process with a healthy
path must return the original exact receipt. This is a bounded lost-query/reply
experiment, not a sustained outage, quorum-loss or connection-establishment test.

For publication, the server persists a Proposal containing expected and proposed
Version (guard plus root) before sending its conditional update. A driver exception
is ROOT_PUBLICATION_UNKNOWN, exit75; it is not a rejection or a safe fallback to the
cache. Tests drop the exact CAS before send or drop the actual applied reply. The
reply decoder checks ROWS metadata, a single boolean [applied] column/row and true.
A fresh SERIAL read through another session must show the complete expected or
proposed guard/root, according to the independently witnessed cut. Offer installation
has not started and cache bytes must still be unchanged at this boundary.

## Recovery and ambiguity

The recorded proposal guard allows three observation results:

| Observation | Meaning |
|---|---|
| Exact proposed guard and root | PUBLISHED: this exact proposal is currently authoritative. |
| Exact expected guard and root | UNCHANGED at this read; not a timeless promise that an in-flight request cannot arrive later. |
| Any other guard/root | UNKNOWN: a later writer may hide whether this proposal ever applied. |

A new process loads authoritative facts, recovering the same signed edit identity
and exact receipt. Only an actually published reservation is added to the serial
archive trace; the dropped-before-send reservation stays an explicitly staged
witness. Retries never treat a timeout as a definitive failure.

A later-writer control installs the same root content with a different guard.
Resolution must remain UNKNOWN despite payload equality, and retrying the captured
old conditional proposal must return not-applied without changing the later root.
There is no immutable publication-result ledger here; historical resolution after
supersession is deliberately left uncertain. Exact observation and the independent
wire cut are separate evidence from the existing serial archive oracle.

## Evidence and limits

`build/evidence/cassandra-root/wire-*` contains process inputs/results, original
proposal, protocol request/reply bytes, exact observed root, classification, unchanged
state and checked histories. A stopped proxy is discarded before the recovery read.
Driver timeout causes are preserved; saved proposed facts are not treated as durable
until the authority read establishes their publication.

Existing combined-checkpoint, split-file, stale-root CAS and rollback-negative
cases remain in the suite. The current candidate is serial and single-writer.
Constructor connection failure, sustained authority unavailability, an overlapping
hot-state writer, arbitrary Paxos phases, power loss, independent-host recovery and
whole-cluster rollback are not established. Proposal-file writes are process-level
fixtures, not a power-loss-qualified SDK journal. No Atlas proof gate or nine-node
HA certification follows from these tests.

The separate AT-089/090 additions passed in run 37421632336: competing root
publications and witnessed minority quorum loss, with downloaded evidence inspected
in the adversarial review. They do
not turn this serial archive/offer fixture into a concurrent cross-store protocol.
