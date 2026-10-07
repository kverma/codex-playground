# Reading the consolidated test report

Each Actions run now produces `atlas-final-test-report`. Download and extract
the complete artifact, then open `index.html`. Search by scenario ID, objective,
class, job or outcome. Each execution has its own page with:

1. The objective and required outcome from the scenario catalog.
2. The actual fixture source, including sample values, generated seeds and operations.
3. Cassandra statement templates and positional values captured at execution.
4. Evaluated expected/actual assertion operands, comparison result and source location.
5. The JUnit outcome and links to the complete trace and original evidence.

For example, AT-001 starts with a $5.00 offer (`cents=500`). It accepts a
$6.00 edit (`600,false`), then expands eligibility (`600,true`). Retrying the
first operation must return its original receipt while the current offer stays
at `600,true`. Reusing the operation ID with `700,false` must raise `KeyReuse`.
The report records the actual receipt, current terms and exception class.
The model executes `store.commit`; the Cassandra execution additionally records
the real conditional batch template and its bound UUIDs, hashes and prices.

For AT-002, two edits use the same saved concurrency token: `600,false` and
`500,true`. The expected number of successful edits is **1**. The trace records
the observed count and checks that the stored token changed. Either editor may win.

For recovery cases, `HEAD` is the current state, `OP:<id>` is an accepted operation's
receipt, a guard is a concurrency token, and the archive floor marks the pruned
prefix. The fixture and raw histories retain full before/after states. A deliberate
broken-control rejection is a successful test when it is the required outcome.

## Evidence boundaries

The first 200 events are shown on each HTML case page. **Every recorded event** is
retained in that case's `.jsonl.gz` file; every original artifact file is included
under `raw/` and listed with a SHA-256 checksum in `evidence-manifest.json`.
Large generated schedules are retained in full, not counted as additional JUnit tests.

The assertion wrapper delegates to JUnit's original comparisons; it does not
replace the independent protocol checkers. Some assertions intentionally throw
inside `assertThrows`; their inner FAIL record is not the final case outcome.
Assertions without a captured overload, setup/teardown failures and interrupted
processes still retain their JUnit/protocol evidence. Missing value capture is
explicitly labelled. A boolean assertion records the predicate result; its source
location explains what was evaluated.

CQL records contain a template and positional parameters, not an interpolated
statement claimed to have been sent verbatim. Schema setup and child-JVM CQL are
not captured by the parent-test recorder. Child-JVM durable histories and wire
evidence remain the authoritative record of those boundaries. Upstream JDK11
tests retain their original comparator and add expected/actual row logging; their
fixture sources and logs are included. These diagnostic overlays do not alter
Cassandra server code.

An uncertain timeout is recorded as an exception, never inferred to be a rejected
write. Model operations are explicitly labelled and are not presented as executed
CQL. No actual values are reconstructed from a green test badge. Unexecuted
scenarios, missing jobs, skipped cases and failures remain visible.

This is bounded PoC evidence, not PG-COMMIT, PG-CASS or production E2E certification.

## Rebuild from downloaded artifacts

Place each job's extracted artifact in a separate subdirectory, then run:

```sh
python3 scripts/test-report.py /path/to/artifacts /path/to/report \
  --run-url https://github.com/OWNER/REPO/actions/runs/RUN \
  --commit EXECUTED_SHA --require-traces
```

The report generator accepts incomplete/failed runs and preserves their evidence.
`--require-traces` fails the reporting gate after saving the report if a non-skipped
Atlas case lacks its captured trace, or if no executions were supplied.
