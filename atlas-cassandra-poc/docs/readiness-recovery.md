# Authoritative reads after readiness changes

## Verified hosted result

[Run 37262063060](https://github.com/kverma/codex-playground/actions/runs/37262063060) passed at
`98d814a66cf93730d496b731266b216381695acc`: 105 distinct POC cases plus 16 upstream.
Downloaded artifacts confirm the intended SERIAL quorum failure in both cases,
exact recovery after two reads, persistent-readiness failure after one read, and
successful cleanup. The initial failed exception-shape assertion and its correction
are preserved in the [adversarial review](adversarial-test-review.md).

## Atlas question

A successful readiness check describes a moment in time. If replicas disappear
before the next offer read, Atlas must not invent an authoritative answer or lose
accepted terms while recovering. These are test-harness recovery checks for the
retention candidate, not a production readiness service.

## Executable conditions

`HealedReads` is shared by the existing six-history recovery suite, shallow
controls and two new three-node cases. It allows at most three authoritative reads.
Only INDETERMINATE permits another attempt, and every retry requires a fresh
readiness check. A readiness failure stops recovery immediately; a definite read
failure is propagated. All attempts retain their outcome and underlying cause.
Callers still require complete state equality.

| Case | Forced condition | Required result |
|---|---|---|
| Shallow recovery | First read is uncertain | Read → readiness → read ordering; exact original view returned |
| Shallow persistent uncertainty | All reads remain uncertain despite readiness | Exactly three reads, two readiness checks, failure with original cause |
| Shallow failed readiness | Readiness throws after the first uncertain read | No second read |
| Shallow definite failure | A non-ambiguous failure occurs | No readiness retry; preserve original failure |
| Three-node recovery | All membership views report three Up/Normal nodes; then isolate dc1 | Witness one Up/Normal and two Down/Normal replicas in dc1, then an actual UnavailableException requiring two replicas but finding one |
| Three-node persistent isolation | Keep the same verified partition active | Fresh membership readiness fails, no second read or successful view; isolation/counter witnesses remain present |

The recovery case heals only after the first failed read. Fresh readiness must
then precede the next attempt. The entire recovered view—including generation,
allocation, floor, commercial state and retained receipt—must equal the saved
view. Replaying the surviving edit returns its original receipt; the retired edit
stays closed. Neither operation may alter the offer.

The fixture starts with two accepted edits: price changes to 400, then royalty to
2500. A controlled clock expires and prunes only the first receipt. This gives the
recovery check both a nonzero retirement floor and a surviving immutable receipt.

## Independent witnesses and failure bounds

The existing partition script verifies bidirectional TCP blocking, positive
iptables DROP counters and a reachable client port. The new `isolated` action
waits up to 60 seconds for dc1's recorded membership view to show one Up/Normal
and two Down/Normal replicas. Generic client timeouts or connection failures
cannot substitute for the required UnavailableException and replica counts. When
the driver wraps errors in AllNodesFailedException, the test requires exactly one
eligible coordinator in dc1 and every recorded per-node cause must be the same
required-two/alive-one UnavailableException. Empty or mixed-cause wrappers fail.

Normal readiness retains its 90-second polling budget. The persistent-isolation
negative control explicitly uses a five-second polling budget; each nodetool
command is separately bounded, so this is not a five-second wall-clock SLO.
The caller bounds the whole command at 120 seconds. Cleanup always heals this
fixture's partition and waits for membership recovery, preserving evidence even
on failure. No host firewall rules or unrelated containers are changed.

`make grade-model` now compiles integration fixtures before hosted Docker jobs
start; it still runs only shallow tests. `make build` also compiles those fixtures.
Run the real cases with `make three-up && make grade-retention`.

Evidence is under `build/evidence/readiness-boundary/{recovered,persistent}/`:
ordered events, every read attempt, exact saved/recovered state, and per-command
logs. Full nodetool snapshots are in `membership-*` and `isolated-*` directories.
The scenario catalog describes AT-072–077 in Atlas terms.
The persistent case also asserts the exact bounded membership failure message
and dc1 up_normal=1 in the fresh readiness log; an arbitrary nonzero shell exit
cannot satisfy this control.

## Limits

This proves two deterministic boundaries on three containers with RF1 per logical
DC. It does not simulate arbitrary flapping, RF3/DC, independent-region failure,
WAN latency or continuous availability between sequential readiness snapshots.
The shared controller is a test harness; production API recovery and its latency
budget remain separate design work. No canonical Atlas proof gate is certified.
