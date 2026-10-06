# Atlas POC continuation checkpoint

Updated 2026-10-06 UTC. Branch `atlas/cassandra-commit-poc`, draft PR #1.

Current executable: `85e608d033f180518a5b746b09b0e2bcba421a26`.
[Actions run 37513953485](https://github.com/kverma/codex-playground/actions/runs/37513953485):
all five jobs passed and all five artifacts were downloaded and audited.
**180 distinct tests = 160 POC +4 Atlas overlay +16 upstream**, with no failures,
errors or skips. The 119 single/model/fault cases include 66 repeated shallow
cases; 31 three-node and ten recovery-phase cases complete the POC total.
The catalog has 119 scenarios. The recovery grader took 19 minutes 30 seconds.

The persistent archive coordinator now connects saved actor proofs and exact phase
requests to real Cassandra journals and local immutable archives. Its audited
evidence includes 85 complete phase histories, 170 journal chains, 41 process cuts
across 82 JVMs and twelve real wire cuts across 24 more JVMs. Cross-DC cases cover
takeover, historical receipts, corruption, competing owner starts, offer/freeze and
maintenance/freeze races. An actual unsafe refreshed-guard overwrite is rejected.
Unpublished checkpoint/archive bytes cannot substitute for committed state.
Earlier 15,840-schedule archive and 3570-schedule fence evidence remains passing.

The identified persistent-phase gap is closed for this bounded price/receipt
contract. The new fixture has per-actor checkpoint locks, not a global recovery
lock. The older richer archive fixture remains separate; this does not replace
production commercial schemas or introduce an authenticated multi-service runtime.
Journal receipts and old archive objects remain unbounded.

Further work requires selected retry-retirement/authorization/event contracts,
archive/authority backends and durability/rollback guarantees, RF3-per-DC capacity,
or independent failure domains. Those are real missing inputs, not a claim that
no additional arbitrary bounded test could be invented in Actions.
No larger runners or services were provisioned. PG-COMMIT/PG-CASS and production
E2E remain UNPROVEN; the PR is draft and unmerged.

Read [the review](adversarial-test-review.md),
[persistent coordinator](persistent-archive-coordinator.md),
[phase model](archive-fence-model.md), and
[validation roadmap](github-actions-validation-roadmap.md) before extending it.
