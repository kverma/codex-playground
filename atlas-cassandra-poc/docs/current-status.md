# Atlas POC continuation checkpoint

Updated 2026-10-07 UTC. Branch `atlas/cassandra-commit-poc`, draft PR #1.

Current executable: `0ffc675a69a770db2effba81a942d0cda5c015cd`.
[Actions run 37579267625](https://github.com/kverma/codex-playground/actions/runs/37579267625):
all five test jobs and the new consolidated-report job passed. All five source
evidence artifacts were downloaded; the report was independently rebuilt and checked.
**180 distinct tests = 160 POC +4 Atlas overlay +16 upstream**, with no failures,
errors or skips. The 119 single/model/fault cases include 66 repeated shallow
cases; 31 three-node and ten recovery-phase cases complete the POC total.
The catalog has 119 scenarios; AT-061 (nine-node HA) remains unexecuted.

## Sample data, DML and expected/actual report

The `atlas-final-test-report` Actions artifact consolidates **246 executions**
(180 distinct tests), executable sample fixtures, test objectives, required outcomes,
captured Cassandra templates/parameters and actual checked values. Extract the
bundle and open `index.html`; see [the report guide](test-evidence-report.md).

All 226 Atlas executions have complete case traces and recorded comparisons:
**897,451 assertion events**, **10,804 CQL requests**, 10,759 driver responses
and 45 exception observations. Exceptions do not imply that a write failed.
All twenty upstream/overlay cases also have captured checked values, including
the actual exception class/message in six phase-timeout cases. Comparisons still
delegate to the original JUnit or pinned Cassandra comparator.

The report retains **42,886 raw files** with SHA-256 checksums. Its HTML previews
the first 200 events per case; complete compressed traces and generated schedules
remain in the bundle. No missing value is inferred from a green test badge.
The report's failure/skipped/missing-evidence handling, links and checksums were
checked independently. Model operations are not represented as executed CQL.

The preceding reporting runs 37578543795 and 37578941181 also passed all six jobs.
The latest run adds direct ExpectedException-matcher observations to the upstream
capture. No protocol acceptance or independent-oracle rules were changed.

## Persistent recovery checkpoint

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
