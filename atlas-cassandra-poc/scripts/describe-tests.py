#!/usr/bin/env python3
"""Generate human-facing Atlas scenario documentation and JUnit display names."""
import csv
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
write = '--write' in sys.argv
with (root / 'docs/test-scenarios.tsv').open() as f:
    rows = list(csv.DictReader(f, delimiter='\t'))
by_method = {r['method']: (i + 1, r) for i, r in enumerate(rows)}
assert len(by_method) == len(rows), 'Duplicate scenario method'
seen = set()
changed = []
for file in sorted((root / 'src').rglob('*.java')):
    source = file.read_text()
    clean = re.sub(r'    // BEGIN ATLAS SCENARIO\n.*?    // END ATLAS SCENARIO\n', '', source, flags=re.S)
    def describe(match):
        method = match.group(2)
        assert method in by_method, f'Missing human scenario: {file.name}.{method}'
        seen.add(method)
        number, row = by_method[method]
        title = f"AT-{number:03d} | {row['goal']}"
        comment = '\n'.join('     * ' + key + ': ' + row[field] for key, field in [('Goal', 'goal'), ('Boundary', 'boundary'), ('Expected', 'outcome')])
        return ('    // BEGIN ATLAS SCENARIO\n    /**\n' + comment + '\n     */\n'
                '    @org.junit.jupiter.api.DisplayName(' + json.dumps(title) + ')\n'
                '    // END ATLAS SCENARIO\n' + match.group(0))
    result = re.sub(r'(^    @Test[^\n]*?\bvoid\s+)(\w+)(?=\()', describe, clean, flags=re.M)
    if source != result:
        changed.append(str(file.relative_to(root)))
        if write:
            file.write_text(result)
assert seen == set(by_method), f'Unused descriptions: {set(by_method) - seen}'
intro = '''# Atlas test scenarios in plain language

These are test goals, not a claim that every production Atlas flow is implemented.
An **offer** here is one bounded commercial-terms subject: price, eligibility and
royalty. **Draft** means an edit prepared against observed terms; **ticket** is its
retry permission; **receipt** records the exact accepted before/after states.
**Retirement/pruning** removes expired retry records without reopening old edits.
**Sealing** closes a prefix before archiving it. **Fencing** blocks authoring until
recovery can establish trustworthy history. An **intent ID** identifies commercial
content; it is not a date or an increasing revision number.

For example: a January offer is for NEW customers; a February edit also includes
CHURNED customers. They are different intents. Observing February's unchanged
configuration in March does not create another intent. Dates here are examples:
business effective-date fields and downstream notifications are not implemented.

## What each test layer means

| Layer / Make command | What actually runs | What it cannot establish |
|---|---|---|
| `make grade-model` | In-memory contract, history and deliberately broken-implementation tests | Cassandra behavior, physical crashes or remote archive durability |
| `make grade-cassandra` | Real Cassandra4.0.5, including the new archive fixture; shared contracts also run in the model | Multi-DC availability or a production archive service |
| `make grade-faults` | Actual batch-frame faults and verified process kills; archive-before-prune process recovery | Power loss, disk fsync guarantees or all internal Paxos phases |
| Three-node graders, including `make grade-archive` | Real coordinators in three logical DCs on one hosted runner | Independent-host/WAN failures, RF3/DC or global restoration fencing |
| `make grade-maintainer` | Sixteen upstream Cassandra phase tests and four Atlas batch/repair overlays, in a separate JDK11/Ant build | Production adapter or remote archive qualification |
| `make grade-recovery-phase` | Persistent recovery phases, actor races, new-JVM and wire cuts | Independent-host durability or a production archive backend |
| `make grade-full-ha` | Separate nine-node scaffold | No qualified execution evidence yet |

The archive Cassandra fixture uses real HEAD and slot rows, a conditional batch,
and stale-guard rejection. The signed-client extension adds persisted client
identities; the durable-server extension adds a local filesystem checkpoint and
real server-process exits. Other archive tests retain in-memory external facts.
Persistent recovery tests also overlap actors and maintenance operations. Restore replaces logical hot rows, not SSTables
or a whole database backup. Independent remote authority/archive services, arbitrary
old deployments and a cross-DC recovery barrier remain open.

The same scenario ID may appear under model and Cassandra classes because they
share a contract. Generated histories and broken variants are iterations inside
a JUnit case, not extra case counts. The IDs below also appear in JUnit display
names and source comments. The TSV keeps developer method mappings separate from
the user-facing explanations.

## Goals, simulated boundaries and required outcomes

| Scenario | Goal in Atlas terms | Simulated boundary | Required outcome |
|---|---|---|---|
'''
guide = intro + ''.join(f"| AT-{i:03d} | {r['goal']} | {r['boundary']} | {r['outcome']} |\n" for i, r in enumerate(rows, 1))
guide += '''
## Reading failures and evidence

A timeout means the result is uncertain; it does not prove the edit failed.
Passing requires the stated state/receipt checks plus any progress gate. A bounded
checker result of INCONCLUSIVE never counts as a pass. Negative controls must fail
the independent checker for the intended reason; a thrown infrastructure error
alone is not a successful negative control.

`build/reports/tests/` contains the readable reports. `build/evidence/` contains
replayable histories and fault witnesses. Archive Cassandra histories and stale
writer witnesses are under `archive-cassandra-single/` and
`archive-cassandra-three/`; the process-crash case is in the single-node folder.
See [the adversarial review](adversarial-test-review.md) for the current run status,
failed attempts and unproven boundaries. All canonical Atlas proof gates remain
unproven.

To maintain this guide, edit `docs/test-scenarios.tsv`, run
`python3 scripts/describe-tests.py --write`, then `make check-test-descriptions`.
The check fails if a test lacks a description or generated text is stale.
'''
file = root / 'docs/test-scenarios.md'
if not file.exists() or file.read_text() != guide:
    changed.append(str(file.relative_to(root)))
    if write:
        file.write_text(guide)
if changed and not write:
    raise SystemExit('Descriptions need regeneration: ' + ', '.join(changed))
print(f'{len(rows)} Atlas scenarios described; ' + ('generated' if write else 'verified'))
