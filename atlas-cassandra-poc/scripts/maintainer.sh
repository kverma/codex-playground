#!/usr/bin/env bash
# Upstream tests are intentionally isolated from Atlas's JDK25 test JVM.
set -euo pipefail
project_dir=$(cd "$(dirname "$0")/.." && pwd)
evidence_dir="$project_dir/build/evidence/maintainer"
mkdir -p "$evidence_dir"
rm -f "$evidence_dir/summary.json"
: "${CASSANDRA_MAINTAINER_JAVA_HOME:?Set the path to a separate JDK11 installation}"
command -v ant >/dev/null
command -v git >/dev/null
command -v python3 >/dev/null
upstream_java="$CASSANDRA_MAINTAINER_JAVA_HOME/bin/java"
"$upstream_java" -version 2> "$evidence_dir/java.txt"
if ! "$upstream_java" -XshowSettings:properties -version 2>&1 | grep -Eq 'java.specification.version = 11$'; then
    echo 'The pinned upstream tests require JDK11' >&2
    exit 1
fi
# Annotated cassandra-4.0.5 tag resolves to this immutable source commit.
source_sha=ec476e0e259efb62ee19804c3ff46dbbe4d1ded7
source_dir=$(mktemp -d "$project_dir/build/upstream-4.0.5.XXXXXX")
collect() {
    local status=$?
    if [[ -d "$source_dir/build/test/output" ]]; then
        rm -rf "$evidence_dir/test-output"
        cp -a "$source_dir/build/test/output" "$evidence_dir/test-output"
    fi
    echo "$status" > "$evidence_dir/exit-status.txt"
}
trap collect EXIT
git -C "$source_dir" init -q
git -C "$source_dir" remote add origin https://github.com/apache/cassandra.git
git -C "$source_dir" fetch -q --depth 1 origin "$source_sha"
git -C "$source_dir" checkout -q --detach FETCH_HEAD
[[ $(git -C "$source_dir" rev-parse HEAD) == "$source_sha" ]]
printf '%s\n' "$source_sha" > "$evidence_dir/source-sha.txt"
cp "$project_dir/maintainer/AtlasBatchPhaseTest.java" "$source_dir/test/distributed/org/apache/cassandra/distributed/test/AtlasBatchPhaseTest.java"
cp "$project_dir/maintainer/AtlasBatchPhaseTest.java" "$evidence_dir/AtlasBatchPhaseTest.java"
export JAVA_HOME="$CASSANDRA_MAINTAINER_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
unset CASSANDRA_USE_JDK11
cd "$source_dir"
# Upstream embeds the full CSV method list in its report filename (>255 bytes here).
# Shorten only report paths; leave server code, tests and their JVM flags unchanged.
python3 - <<'PY'
from pathlib import Path
p = Path('build.xml')
text = p.read_text()
before = 'outfile="build/test/output/TEST-${test.name}-${test.methods}"'
assert before in text
p.write_text(text.replace(before, 'outfile="build/test/output/TEST-${test.name}"'))
PY
git diff -- build.xml > "$evidence_dir/report-path.patch"
cas_methods=simpleUpdate,incompletePrepare,incompletePropose,incompleteCommit,readConsistencyAfterWriteTimeoutTest,nonApplyingCasConsistencyAfterWriteTimeout,mixedReadAndNonApplyingCasConsistencyAfterWriteTimeout,mixedNonApplyingCasAndReadConsistencyAfterWriteTimeout
write_methods=testCasWriteSuccessWithNoContention,testCasWriteTimeoutAtPreparePhase_ReqLost,testCasWriteTimeoutAtPreparePhase_RspLost,testCasWriteTimeoutAtProposePhase_ReqLost,testCasWriteTimeoutAtProposePhase_RspLost,testCasWriteTimeoutAtCommitPhase_ReqLost,testCasWriteTimeoutAtCommitPhase_RspLost,testWriteUnknownResult
ant -Duse.jdk11=true test-jvm-dtest-some \
    -Dtest.name=org.apache.cassandra.distributed.test.CASTest \
    -Dtest.methods="$cas_methods" 2>&1 | tee "$evidence_dir/cas.log"
ant -Duse.jdk11=true test-jvm-dtest-some \
    -Dtest.name=org.apache.cassandra.distributed.test.CasWriteTest \
    -Dtest.methods="$write_methods" 2>&1 | tee "$evidence_dir/cas-write.log"
atlas_methods=headReceiptAtEveryPaxosMessageBoundary,floorDeleteAtEveryPaxosMessageBoundary,atomicityOracleRejectsAnActualSplitPrune,editCompletesInsideWitnessedRepairValidationPause
ant -Duse.jdk11=true test-jvm-dtest-some \
    -Dtest.name=org.apache.cassandra.distributed.test.AtlasBatchPhaseTest \
    -Dtest.methods="$atlas_methods" 2>&1 | tee "$evidence_dir/atlas-batch.log"
# Guard against empty, skipped, or silently unselected suites, independently of Ant's exit code.
python3 - "$source_dir/build/test/output" "$evidence_dir/summary.json" "$cas_methods" "$write_methods" "$atlas_methods" <<'PY'
import json, pathlib, sys, xml.etree.ElementTree as ET
expected = {"org.apache.cassandra.distributed.test.CASTest": set(sys.argv[3].split(",")),
            "org.apache.cassandra.distributed.test.CasWriteTest": set(sys.argv[4].split(",")),
            "org.apache.cassandra.distributed.test.AtlasBatchPhaseTest": set(sys.argv[5].split(","))}
seen = {name: set() for name in expected}
assert sorted(map(len,expected.values())) == [4,8,8]
for path in pathlib.Path(sys.argv[1]).rglob("*.xml"):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        name = case.get("classname")
        if name not in expected:
            continue
        assert not any(case.find(tag) is not None for tag in ("failure", "error", "skipped")), (path, case.attrib)
        method = case.get("name")
        assert method in expected[name] and method not in seen[name], (path, case.attrib)
        seen[name].add(method)
assert seen == expected, (seen, expected)
pathlib.Path(sys.argv[2]).write_text(json.dumps({"tests": {name: sorted(methods) for name, methods in seen.items()}, "failures": 0, "scope": "16 upstream smoke + 4 Atlas-authored batch/repair cases; not adapter or durability certification"}, indent=2) + "\n")
PY
