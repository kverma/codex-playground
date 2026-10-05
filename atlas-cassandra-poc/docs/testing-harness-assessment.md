# Testing layers and CassandraUnit assessment

## Latest addition — readable scenarios and Cassandra archive fixture

[Run 37249882512](https://github.com/kverma/codex-playground/actions/runs/37249882512) at
`6a25c98acff1d24c230875f06eaf616d8467ad46` passed all four jobs. Downloaded XML confirms
47 shallow, 81 model/single-node/fault, 15 three-node and 16 upstream cases:
**96 distinct POC cases plus 16 upstream tests** after deduplicating shallow reruns.

The [plain-language guide](test-scenarios.md) supplies Atlas goals, simulated
boundaries and required outcomes for all 68 unique local scenarios. JUnit reports
show their readable titles; `make check-test-descriptions` passed and guards against
missing/stale descriptions. Inherited contracts reuse scenario IDs across fixture
classes. [Upstream explanations](upstream-test-scenarios.md) cover all 16 selected
vendor methods without renaming or modifying those pinned tests.

The [new fixture](archive-cassandra-fixture.md) adds six single-node and six
cross-coordinator archive scenarios plus a process-crash case. Its two suites
preserve seven/six VALID histories and ten INVALID negative-control histories each.
Captured stale CQL fails after sealing, restore and recovery; the deliberate
guard bypass applies and is rejected by the independent checker. Real Cassandra
state is read back after guarded batches. External archive/authority, orchestration
and logical restoration retain explicit fixture assumptions.

## Decision

Use `make grade-model` as the shallow, Docker-free JDK25 gate. Run it first in
GitHub Actions; both Docker jobs depend on it. Keep the real single-node and
three-node Cassandra4.0.5 graders. Add `make grade-maintainer` as a separate
upstream phase-control smoke test. No personal machine is required.

## CassandraUnit: useful fixtures, incompatible embedded server

Checked 2026-10-04 against the project's source revision
[`3cc1387`](https://github.com/jsevellec/cassandra-unit/tree/3cc1387f0fed74e2b3fff4b68c85410952af3e4e).
Its [README](https://github.com/jsevellec/cassandra-unit/blob/3cc1387f0fed74e2b3fff4b68c85410952af3e4e/README.md),
[POM](https://github.com/jsevellec/cassandra-unit/blob/3cc1387f0fed74e2b3fff4b68c85410952af3e4e/pom.xml)
and [embedded setup](https://github.com/jsevellec/cassandra-unit/blob/3cc1387f0fed74e2b3fff4b68c85410952af3e4e/docs/getting-started.md)
show two distinct uses:

| Option | Fit for this POC |
|---|---|
| Old `cassandra-unit:4.3.1.0` embedded server | Embeds Cassandra3.11.5, despite its name; cannot supply 4.0.5 evidence. |
| Current embedded server | Source pins Cassandra5.0.8 and documents JDK17 only; not our 4.0.5/JDK25 test JVM. One node per JVM also cannot replace distributed tests. |
| Current `cassandra-unit-dataset` | Driver-only loading/assertions can use an external session on newer JDKs. It does not start Cassandra or inject distributed faults. Current extensions require Jupiter6; our POC uses Jupiter5. |
| Override Cassandra dependencies to 4.0.5 | A new compatibility experiment, not an established supported harness. Do not add it to a proof gate without a passing pinned compatibility probe. |

Do not add CassandraUnit now. Our small contracts already construct synthetic
fixtures and assert exact state/receipts. Dataset helpers may be useful when
larger schema fixtures exist; evaluate them separately without changing the server
pin or test framework just to shorten fixture setup. No embedded compatibility
probe has been executed; this decision is based on upstream requirements/source.

## How Apache maintainers test correctness

The [official testing guide](https://cassandra.apache.org/_/development/testing.html)
describes node-local CQL/JUnit tests, classloader-isolated in-JVM distributed
tests, and Python/CCM process-cluster tests. These serve different failure scopes.
Current guide commands for newer branches must not be assumed to apply to 4.0.5.

The exact [4.0.5 `CASTest`](https://github.com/apache/cassandra/blob/cassandra-4.0.5/test/distributed/org/apache/cassandra/distributed/test/CASTest.java)
drops Paxos phase messages and checks what later SERIAL operations observe after
incomplete writes. [4.0.5 `CasWriteTest`](https://github.com/apache/cassandra/blob/cassandra-4.0.5/test/distributed/org/apache/cassandra/distributed/test/CasWriteTest.java)
separately drops prepare/propose/commit requests or responses and tests unknown
results. This is more precise than observing a client frame before a Docker kill.

[Python Paxos dtests](https://github.com/apache/cassandra-dtest/blob/trunk/paxos_test.py)
exercise replica/cluster availability and contention through CCM.
[Harry](https://github.com/apache/cassandra-harry) generates reproducible data
workloads and validates reads against expected state, including repair scenarios.
Harry's generic data model does not specify Atlas operation identity, group read
sets or retention floors; retain our independent application oracle. Harry or
newer simulator code needs a separate version compatibility assessment.

## Runnable upstream phase smoke

```sh
# Atlas Gradle still runs on JDK25. Install JDK11 separately for upstream Ant.
export CASSANDRA_MAINTAINER_JAVA_HOME=/path/to/jdk11
make grade-maintainer
```

The script fetches Cassandra4.0.5 source commit
`ec476e0e259efb62ee19804c3ff46dbbe4d1ded7` into a disposable build directory.
Gradle invokes upstream Ant with JDK11. It selects eight tests from each class,
requires the exact 16 selected method names with no duplicates, failures or skips,
and saves source identity,
Java version, logs and reports under `build/evidence/maintainer/`. Only report
filenames are shortened to fit filesystem limits; that patch is saved as evidence.
The GitHub job installs Ant and provisions both JDKs on its disposable runner.
Synthetic XML guard checks accept the complete selected suite and reject empty,
missing, duplicate, wrongly named, skipped, failed and errored results. This is
verification of report selection, not a replacement for running the upstream tests.
CI triggers on pushes and manual dispatch. Push checks attach to the PR's head
commit, avoiding duplicate push/PR-synchronize execution in this repository.

Upstream in-JVM test flags skip disk sync and use classloader-isolated nodes.
Passing these upstream tests validates those selected Cassandra behaviors, not
the Atlas adapter, real sockets, power-loss durability or all Paxos interleavings.
Protocol-phase fault injection into our actual conditional HEAD/receipt batch is
still a separate task; existing Docker faults do not identify an internal phase.

## Ranked layers and outstanding work

| Order | Gate | Evidence boundary |
|---|---|---|
| 1 | `grade-model` | Fast contract/oracle/mutant feedback; no real Cassandra. |
| 2 | `grade-cassandra` | Actual pinned server, CQL batch, codec and restart contracts. |
| 3 | `grade-maintainer` | Selected upstream phase controls; independent smoke, not adapter coverage. |
| 4 | Three-node scenario/history graders | Real processes, verified partition/crash witnesses and bounded application histories. |
| 5 | Adapter phase tests in pinned maintainer harness | Next: instrument actual HEAD/receipt CQL, exact retry, atomicity and floor/delete behavior; prove each selected message was intercepted. |
| 6 | RF3/DC and independent-host qualification | Nine-node remote run, skew/restore/archive/GC, WAN and disk/power failures remain open. |

The signed-draft allocator and independent bounded retention oracle address the
candidate-level collision and specification gaps. Production SDK persistence,
key/clock policy, longer/skewed histories and adapter phase witnessing remain open.
Selecting a different harness does not close those gates. Atlas proof gates remain
UNPROVEN.

## Archive/restore model addition — 2026-10-05

The [archive/restore specification](archive-restore-state-machine.md) adds ten
shallow test methods: 100 generated serial traces, 16 before/after fault-boundary
traces, nine contract traces and nine actual broken variants with saved replay.
The independent checker keeps its acceptance history across restoration and checks
full archive contents independently of implementation digests. Step or nested
commercial-checker bound exhaustion remains INCONCLUSIVE.

This gate passed on JDK25/Gradle9.1.0 at executable commit
`ad48e24bcedfcfd073898ad02879a7704a2d8985`. Downloaded XML and JSON confirm the
counts and expected verdicts. It adds no Cassandra archive adapter, remote archive
service, bounded recovery-authority representation or enforceable global fence.
Those are the next integration gates; the existing retention adapter still prunes
without an archive requirement. Business effective-time/event fields remain absent.

## Previous archive-model cloud execution

Executable commit `bc5c9e00284c234e5653dd81314307b0b90f4994` passed all four jobs in
[run 37248385829](https://github.com/kverma/codex-playground/actions/runs/37248385829).
Downloaded XML confirms **83 distinct POC cases plus 16 upstream tests**, with no
failures, errors or skips: 47 shallow cases are included in the 74-case
model/single-node/fault job; nine three-node cases complete the POC total.

All four artifacts were downloaded and inspected. Archive evidence contains 100
VALID generated traces, 16 VALID boundary traces, nine VALID contract traces and
nine INVALID mutant counterexamples. The three-node archive contains 14
LINEARIZABLE commercial histories and six LINEARIZABLE retention histories.
All 18 healed coordinator views exactly match the checked final states.

The compaction scenario and its two controls passed. All real compaction/healed
reads resolved on the first attempt in this run: the added healed-read readiness
retry branch was **not exercised**. Deterministically injecting membership changes
during readiness sampling remains an adversarial test gap. One green regression
does not erase the two failed runs below or establish a recovery-latency SLO.

This qualifies bounded fixtures and the separate archive/restore serial model.
The Cassandra archive adapter, remote archive durability, distributed recovery
fence and full provider API/compile/Kafka/downstream E2E flows remain unvalidated.

Build pins remain Cassandra4.0.5, JDK25, Gradle9.1.0 and Make wrappers. Upstream
maintainer smoke still uses its separate JDK11/Ant process. See the
[adversarial review](adversarial-test-review.md) for both failed-run timelines and
the bounded compaction/readiness changes. These are fixture recovery gates, not
production availability guarantees.

## Previous verified cloud execution

[Run 37243518121](https://github.com/kverma/codex-playground/actions/runs/37243518121)
at executable commit `7ba6fd7d27d54218a8747176872d624d1f1bb17b` passed all four jobs.
Completed grader logs confirm:

| Job | Distinct passed cases | Counting boundary |
|---|---:|---|
| Shallow | 37 | Included again in the POC job. |
| Model/single-node/fault | 64 | Build and grader repeat model execution; count each class/method once. |
| Three-node | 7 | Includes the six-seed retention history case and the older retention scenario. |
| Upstream maintainer | 16 | Eight CASTest and eight CasWriteTest methods; exact-selection guard passed. |

That is **71 distinct POC cases plus 16 upstream tests**, with no failed or skipped
cases. Upstream logs report zero failures, errors and skips for both eight-test
suites. Source remains pinned to `ec476e0e259efb62ee19804c3ff46dbbe4d1ded7`;
upstream Ant runs on JDK11, while Atlas remains JDK25/Gradle9.1.0/Cassandra4.0.5.
The filename-only upstream report patch and complete XML are published as artifacts.

The new retention cases require 200 model histories (100 with lost replies), six
real Cassandra histories, seven rejected/replayed storage mutants and a black-hole
progress control. Existing coverage also runs 400 commercial model histories and
14 real Cassandra histories. These counts are enforced by successful grader methods;
earlier downloaded reports independently confirmed the model/mutant counts.

Both retention scenarios recorded three Up/Normal peers from every node before
their healed-state assertions. Every generated healed view must equal the exact
final history state, with at most three read attempts per coordinator. Artifacts
include the membership reports, `retention-healed/`, histories, policy/fault
witnesses, JUnit reports, Cassandra logs and resource snapshot.

Two intervening runs failed on authoritative reads after TCP healing, including
one that exhausted three attempts. The harness now distinguishes TCP reachability,
server peer membership and authoritative read recovery, and retains driver causes.
The passing run qualifies these stronger conditions; it does not identify the
specific cause of the earlier ambiguous reads or prove a recovery-latency SLO.

Artifact downloads initially stalled. The 2026-10-05 continuation downloaded all
four archives for this run and verified XML counts and saved history/mutant
verdicts. The six healed retention traces contain 18 successful coordinator views,
each exactly equal to its checked final history state. All six saved membership
reports show three UN peers. This closes the earlier artifact-download gap.

The previous [baseline rerun](https://github.com/kverma/codex-playground/actions/runs/37238077480/attempts/2)
passed after the repository fix. Its earlier runner-start blocker remains resolved.
Documentation-only changes after the executable revision skip redundant CI.
Canonical Atlas proof gates, RF3/DC, skew/restore/archive/GC and full SDK/API flows
remain unproven.
