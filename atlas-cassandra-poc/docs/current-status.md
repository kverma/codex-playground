# Atlas POC continuation checkpoint

Updated 2026-10-06 UTC. Branch `atlas/cassandra-commit-poc`, draft PR #1.

Current executable: `eed0c99c509d57d7aaaddc5c73638d4000f77742`.
[Actions run 37497216337](https://github.com/kverma/codex-playground/actions/runs/37497216337):
all four jobs passed and all four artifacts were downloaded and audited.
**162 distinct tests = 142 POC +4 Atlas overlay +16 upstream**, with no failures,
errors or skips. The 119 single/model/fault cases include 66 repeated shallow
cases; 23 three-node cases complete the POC total. The catalog has 109 scenarios.

This campaign adds the archive-bound fence model: 15,840 bounded schedules,
283,140 prefixes, seven actual unsafe controls, malformed-copy recovery, and an
expected counterexample when a verified archive disappears before pruning.
It also corrects journal/root contention tests to preserve timeout uncertainty
and require bounded exact recovery. The prior failed runs remain documented.

Next work still possible in standard Actions: persist actor captures and verified
proofs, persist exact per-phase requests, and integrate the tested phase contract
with journal/Cassandra adapters. Define safe journal retirement before treating
archive deletion as loss of the last recovery copy. The existing real archive
fixture uses an in-process lock; the new model is not a persistent coordinator.

External qualification still needs selected archive/authority backends, their
durability/rollback contracts, RF3-per-DC capacity, and independent failure domains.
No larger runners or services were provisioned. PG-COMMIT/PG-CASS and production
E2E remain UNPROVEN; the PR is draft and unmerged.

Read [the review](adversarial-test-review.md),
[phase contract](archive-fence-model.md), and
[validation roadmap](github-actions-validation-roadmap.md) before extending it.
