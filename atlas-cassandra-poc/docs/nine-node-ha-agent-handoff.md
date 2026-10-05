# Nine-node Atlas HA: laptop agent handoff

## Mission and starting point

Run and assess the existing nine-node Cassandra4.0.5 scaffold: three logical data
centers, three replicas per DC. Preserve reproducible evidence and report exactly
what passed, failed or could not run. Do not equate a laptop's nine containers with
nine independent hosts or production multi-region durability.

Repository: `kverma/codex-playground`; branch: `atlas/cassandra-commit-poc`;
[draft PR #1](https://github.com/kverma/codex-playground/pull/1).
The latest verified executable baseline when this guide was written is
`6a25c98acff1d24c230875f06eaf616d8467ad46`, with
[96 POC cases plus 16 upstream tests](https://github.com/kverma/codex-playground/actions/runs/37249882512).
Subsequent documentation commits do not imply another executable validation.
**The nine-node scaffold has no verified execution result yet.**

Read these files before editing or running destructive fixture operations:

1. `AGENTS.md`, `README.md`, `docs/test-scenarios.md` and `docs/adversarial-test-review.md`.
2. `compose.ha.yaml`, `scripts/ha-up.sh`, `Makefile` and `build.gradle.kts`.
3. `src/integrationTest/java/atlas/poc/FullHaTest.java` and
   `src/main/java/atlas/poc/CassandraStore.java`.
4. `docs/archive-cassandra-fixture.md` for what the newer archive tests do **not** prove.

Scope is `atlas-cassandra-poc/` and, if needed, its dedicated Actions workflow.
RevealSwift and unrelated projects must remain untouched. Preserve user changes;
use a separate checkout/worktree and task branch when making improvements. Keep
JDK25, Gradle9.1.0, Cassandra4.0.5, SERIAL and QUORUM. Do not weaken consistency,
silently skip assertions, change test intent or use custom LWT timestamps.

## What the current HA test actually covers

`make grade-full-ha` selects **one** JUnit case, AT-061, in `FullHaTest`:

| Stage | Atlas goal | Existing assertion |
|---|---|---|
| Healthy nine-node ring | Accept a whole-offer edit through dc1 | Save its acknowledged receipt |
| Stop all three dc1 nodes | Recover through another ingress DC | dc2 returns the exact original receipt; six surviving replicas accept a later edit |
| Also stop all three dc3 nodes | Do not acknowledge authoring from a three-replica minority | A dc2 write returns INDETERMINATE |
| Restart nodes | Preserve the earlier result and accepted offer | dc3 replays the original receipt and reads the majority's later terms |

RF is 3 in each DC: nine replicas total; global SERIAL/QUORUM need five replicas.
Six survivors can form that majority; three cannot. This does not guarantee an
immediate successful request while failure detection and connections recover.

This test uses the older `Protocol` whole-offer candidate. It does **not** run the
TermGroup history oracle, signed-draft retention or archive suites with RF3/DC.
Those classes currently hardcode the one-node or three-node topology and ports.
Running `make grade-history`, `make grade-retention` or `make grade-archive` against
this cluster is not a valid shortcut: topology checks should reject it.

## Host requirements and platform decision

These are planning allowances, not measured minimums or performance promises.

| Item | Initial target / rule |
|---|---|
| Memory visible to Docker | At least 24 GiB; each of nine servers has a 1 GiB heap plus native memory and page cache |
| Laptop RAM | Usually at least 32 GiB to leave space for the host and JDK; more headroom is preferable |
| CPU | Prefer at least 8 logical CPUs available to the Linux execution environment; record actual allocation |
| Free Docker storage | Start with 40 GiB free as a planning allowance; monitor growth and preserve logs before cleanup |
| Build tools | Full JDK25 including `javac`, Gradle9.1.0, Bash, GNU Make, Python3, Git, Docker Engine and Compose v2 supporting `--wait` |
| Other JVMs | Cassandra uses its image's JVM; do not replace it with JDK25. JDK11/Ant are needed only for `grade-maintainer`, not this HA case |
| Session | Keep the laptop plugged in and prevent sleep for the run; avoid unrelated heavy workloads |

Choose a platform path explicitly:

* **Native Linux with a local Docker daemon:** closest to the current harness.
  Confirm the test JVM can reach the Docker bridge addresses advertised by all
  nine peers, not only the three initial published contact ports.
* **macOS or Windows Docker Desktop:** Compose can run Linux containers, but the
  Java driver may discover private container IPs that the host JVM cannot route
  to. With three peers in each local DC it can select those discovered endpoints.
  Do not assume that a successful connection to localhost proves all peers work.
  Prefer running Docker, JDK25, Gradle and the checkout **together inside a suitably
  sized Linux VM**. Alternatively, implement and test a driver address translator
  mapping every container endpoint to its distinct published host port. That is
  an explicit harness change; the current code does not provide it.
* **Apple Silicon / other ARM hosts:** inspect the exact `cassandra:4.0.5` image
  manifest first. This handoff does not assume a native ARM variant exists. If an
  amd64 image requires emulation, record that fact, expect different timing, and
  prefer an adequately sized x86 Linux machine for qualification. Never silently
  replace Cassandra with a newer version for architecture convenience.
* **Remote Docker context:** localhost ports refer to the daemon host, not the
  agent's laptop. Run the build JVM on that same host or deliberately implement
  networking/translation; the current hardcoded localhost harness is not remote-
  daemon ready.

The HA test uses Docker CLI process kills, not the three-node `nsenter`/iptables
partition script. Do not run `scripts/three.sh` against this nine-node stack.
Docker access itself is sufficient for its existing kill operations. Use only
synthetic data. Published CQL ports must remain loopback-only; no public exposure
or production credentials are required. Do not register the laptop as a general
self-hosted Actions runner merely to execute these Make commands.

## 1. Acquire an isolated checkout

Use the user's existing GitHub authentication. Do not print credentials or set up
new tokens in scripts. From a directory chosen for this task:

```bash
git clone --branch atlas/cassandra-commit-poc --single-branch \
  https://github.com/kverma/codex-playground.git atlas-ha-work
cd atlas-ha-work
git status --short
git rev-parse HEAD
git switch -c atlas/ha-local-validation
cd atlas-cassandra-poc
```

If the chosen directory/branch already exists, inspect it and pick a new task
name; do not overwrite it. For an existing checkout, use a clean worktree instead
of switching a dirty working directory. Read any applicable parent `AGENTS.md`.

## 2. Preflight and preserve run identity

Use Bash for the following blocks. All commands from here assume the working
directory is `atlas-cassandra-poc`. Keep `ATLAS_HA_EVIDENCE` in the same shell;
if resuming, recover its path from the saved run directory rather than creating a
new directory that loses the connection to earlier logs.

```bash
set -o pipefail
ATLAS_HA_RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
ATLAS_HA_EVIDENCE="$PWD/build/evidence/ha-$ATLAS_HA_RUN_ID"
mkdir -p "$ATLAS_HA_EVIDENCE"
git rev-parse HEAD > "$ATLAS_HA_EVIDENCE/commit.txt"
git status --short > "$ATLAS_HA_EVIDENCE/git-status.txt"
git diff > "$ATLAS_HA_EVIDENCE/local-changes.patch"
uname -a > "$ATLAS_HA_EVIDENCE/host.txt"
java -version 2> "$ATLAS_HA_EVIDENCE/java.txt"
javac -version > "$ATLAS_HA_EVIDENCE/javac.txt" 2>&1
gradle --version > "$ATLAS_HA_EVIDENCE/gradle.txt"
docker version > "$ATLAS_HA_EVIDENCE/docker.txt"
docker compose version > "$ATLAS_HA_EVIDENCE/compose-version.txt"
docker context show > "$ATLAS_HA_EVIDENCE/docker-context.txt"
docker info --format 'OS={{.OSType}} Arch={{.Architecture}} CPUs={{.NCPU}} MemoryBytes={{.MemTotal}}' \
  > "$ATLAS_HA_EVIDENCE/docker-resources.txt"
docker system df > "$ATLAS_HA_EVIDENCE/docker-disk.txt"
docker compose -f compose.ha.yaml config > "$ATLAS_HA_EVIDENCE/compose-effective.yaml"
docker compose ls > "$ATLAS_HA_EVIDENCE/compose-projects.txt"
docker ps --format '{{.Names}}\t{{.Ports}}' > "$ATLAS_HA_EVIDENCE/running-containers.txt"
```

Inspect all results. `docker system df` reports Docker usage, not necessarily free
space in its VM: check the Docker Desktop disk allocation or the Linux daemon's
filesystem as appropriate. If the Docker budget is insufficient, stop here and
report the measured shortage rather than starting an OOM experiment.

The compose project is `atlas-cassandra-ha`, distinct from the single/three-node
project, but **published ports overlap**. Check all nine loopback ports:

```bash
python3 - <<'PY'
import socket
ports = [9042,9043,9044,9142,9143,9144,9242,9243,9244]
busy = []
for port in ports:
    with socket.socket() as s:
        try:
            s.bind(('127.0.0.1', port))
        except OSError:
            busy.append(port)
if busy:
    raise SystemExit(f'Ports already occupied; identify their owners first: {busy}')
print('All nine CQL ports available')
PY
```

If ports or a project already exist, identify ownership. Stop only an Atlas test
stack known to belong to this task; never kill arbitrary listeners or use global
Docker prune. Do not change the compose project name through an override without
also changing every harness command consistently.

Inspect/pull the server image and record its resolved identity:

```bash
docker buildx imagetools inspect cassandra:4.0.5 \
  > "$ATLAS_HA_EVIDENCE/image-manifest.txt"
docker pull cassandra:4.0.5
docker image inspect cassandra:4.0.5 \
  --format '{{.Id}} {{json .RepoDigests}} {{.Os}}/{{.Architecture}}' \
  > "$ATLAS_HA_EVIDENCE/image-identity.txt"
make check-test-descriptions
make build 2>&1 | tee "$ATLAS_HA_EVIDENCE/build.log"
```

`make build` runs the shallow tests; do not also rerun them solely to duplicate
evidence. Stop on any failed command. Do not treat a successful `tee` as the build
status: the shell must have `pipefail` enabled. No Gradle wrapper is bundled;
`gradle` must resolve to 9.1.0. If it is installed elsewhere, set the Make `GRADLE`
variable to its executable path and record that path.

## 3. Start and verify the ring

```bash
make ha-up 2>&1 | tee "$ATLAS_HA_EVIDENCE/startup.log"
docker compose -f compose.ha.yaml ps > "$ATLAS_HA_EVIDENCE/containers-before.txt"
docker stats --no-stream $(docker compose -f compose.ha.yaml ps -q) \
  > "$ATLAS_HA_EVIDENCE/resources-before.txt"
```

`ha-up.sh` starts nodes sequentially, allowing up to 300 seconds per node, then
polls dc1n1 for nine Up/Normal nodes. Allow startup time; its configured waits can
total roughly 46 minutes before command overhead. This is a timeout budget, not
an expected runtime. Do not launch a second startup while the first is running.

Its current readiness check is insufficient for qualification because it samples
only dc1n1. Collect and validate **every node's** view before grading:

```bash
for node in dc1n1 dc1n2 dc1n3 dc2n1 dc2n2 dc2n3 dc3n1 dc3n2 dc3n3; do
  docker compose -f compose.ha.yaml exec -T "$node" nodetool status \
    > "$ATLAS_HA_EVIDENCE/$node-before-status.txt" 2>&1 || exit 1
  count=$(awk '$1 == "UN" {n++} END {print n+0}' "$ATLAS_HA_EVIDENCE/$node-before-status.txt")
  [ "$count" = 9 ] || { echo "$node sees $count Up/Normal peers; not ready"; exit 1; }
  docker compose -f compose.ha.yaml exec -T "$node" cqlsh -e \
    'SELECT release_version,cluster_name,data_center,rack FROM system.local;' \
    > "$ATLAS_HA_EVIDENCE/$node-identity.txt" 2>&1 || exit 1
done
```

Check three nodes in each expected DC, racks 1–3, cluster `atlas-ha` and version
4.0.5 everywhere. These snapshots are readiness evidence, not proof membership
stays unchanged. Verify that advertised node endpoints are reachable by the test
JVM before trusting Docker Desktop results. CQL contact mappings are:

| DC | Services | Host ports |
|---|---|---|
| dc1 | dc1n1, dc1n2, dc1n3 | 9042, 9043, 9044 |
| dc2 | dc2n1, dc2n2, dc2n3 | 9142, 9143, 9144 |
| dc3 | dc3n1, dc3n2, dc3n3 | 9242, 9243, 9244 |

Each maps to container port 9042; internode ports are not published to the host.
Do not pre-create `atlas_poc` with RF1: the test creates it as NetworkTopologyStrategy
with dc1=3, dc2=3, dc3=3 and rejects a mismatched existing keyspace.

## 4. Run the existing smoke and preserve its real result

First run the unmodified scaffold as a baseline. It is allowed to fail; preserve
the reason before making a targeted improvement. Its one scenario is deliberately
destructive **only to these disposable Cassandra containers**.

```bash
set +e
make grade-full-ha 2>&1 | tee "$ATLAS_HA_EVIDENCE/grade-full-ha.log"
ATLAS_HA_GRADE_STATUS=${PIPESTATUS[0]}
printf '%s\n' "$ATLAS_HA_GRADE_STATUS" > "$ATLAS_HA_EVIDENCE/grade-exit-status.txt"
docker compose -f compose.ha.yaml logs --no-color \
  > "$ATLAS_HA_EVIDENCE/cassandra.log" 2>&1
docker compose -f compose.ha.yaml ps -a > "$ATLAS_HA_EVIDENCE/containers-after.txt"
docker stats --no-stream $(docker compose -f compose.ha.yaml ps -q) \
  > "$ATLAS_HA_EVIDENCE/resources-after.txt" 2>&1
docker compose -f compose.ha.yaml exec -T dc2n1 cqlsh -e \
  "SELECT replication FROM system_schema.keyspaces WHERE keyspace_name='atlas_poc';" \
  > "$ATLAS_HA_EVIDENCE/replication.txt" 2>&1
for node in dc1n1 dc1n2 dc1n3 dc2n1 dc2n2 dc2n3 dc3n1 dc3n2 dc3n3; do
  docker compose -f compose.ha.yaml exec -T "$node" nodetool status \
    > "$ATLAS_HA_EVIDENCE/$node-after-status.txt" 2>&1
  id=$(docker compose -f compose.ha.yaml ps -a -q "$node")
  if [ -n "$id" ]; then
    docker inspect --format '{{json .State}} {{json .Mounts}}' "$id" \
      > "$ATLAS_HA_EVIDENCE/$node-state-mounts.txt" 2>&1
  fi
done
```

Diagnostic commands may fail when nodes remain down; record that rather than
replacing the grader exit status. The test's `finally` block attempts restart but
does not guarantee recovery. Reports are under `build/reports/tests/gradeFullHa/`
and `build/test-results/gradeFullHa/`. Check exactly one AT-061 case, with zero
failures/errors/skips and a successful Make exit before calling the **smoke** green.
Do not count a zero-test, skipped, or stale report as success. Match XML timestamps
and the captured commit/run log. Save the reports before any later run overwrites them.

## 5. Harden before calling this HA qualification

The current scaffold predates several adversarial improvements to the smaller
harness. A green smoke alone does not close R12. The next agent should implement
these focused changes on the task branch and rerun with new evidence:

1. **Verified DC loss:** replace bare kill-command success with a witness for all
   three targeted containers: running beforehand, stopped afterward, exit 137,
   no OOM, PID zero. Reuse/adapt `VerifiedKill` without changing unrelated stacks.
2. **Phase-specific availability:** record the six live survivors after dc1 loss
   and exactly three after dc3 loss. Verify RF3/DC and actual coordinator routing.
   Host port reachability alone does not establish replica membership or quorum.
3. **Bounded exact recovery:** preserve each original request and receipt through
   timeouts; record attempts/causes. Do not mint new IDs or refresh dependencies.
   Require progress after recovery, while persistent uncertainty must fail.
4. **Ambiguous minority writes:** the current test expects the majority's value
   after healing. Review possible late/in-flight completion: INDETERMINATE is not
   definitive non-acceptance. Record its interval and exact recovery result, and
   check a valid history rather than relabeling a timeout as rejection. Prove any
   stronger “never applied” assertion with an actual execution/phase witness.
5. **Recovery readiness:** restart in a controlled manner, gather all-node
   membership and bounded authoritative reads, then compare full state/receipts.
   The scaffold currently restarts all stopped services together and reads without
   the stronger readiness checks used by the three-node retention suite.
6. **Independent HA histories:** parameterize the newer TermGroup, signed-draft
   retention and archive fixtures for `full` topology and appropriate coordinator
   endpoints; retain their independent oracles, progress gates and negative
   controls. Do not simply remove topology validation to reuse RF1 tests.
7. **Report clarity:** update `docs/test-scenarios.tsv` for any new/changed scenario,
   regenerate with `python3 scripts/describe-tests.py --write`, and run
   `make check-test-descriptions`. Keep Atlas goals/boundaries explicit.

Do not add every fault at once. Start with one healthy run, then witnessed single
DC loss, then the minority and recovery stages. Save separate evidence directories
for each attempt. On failure, inspect logs and resource pressure before changing
timeouts; a justified bounded recovery policy is different from retry-until-green.

## 6. Archive evidence and clean up only this fixture

Save a self-contained bundle **outside** `build/` so `make clean` cannot erase it:

```bash
ATLAS_HA_BUNDLE="$PWD/../atlas-ha-results-$ATLAS_HA_RUN_ID"
mkdir -p "$ATLAS_HA_BUNDLE"
cp -R "$ATLAS_HA_EVIDENCE" "$ATLAS_HA_BUNDLE/run"
cp -R build/test-results/gradeFullHa "$ATLAS_HA_BUNDLE/test-results"
cp -R build/reports/tests/gradeFullHa "$ATLAS_HA_BUNDLE/report"
if [ -f build/evidence/policy.jsonl ]; then
  cp build/evidence/policy.jsonl "$ATLAS_HA_BUNDLE/"
fi
if [ -f build/evidence/verified-kills.jsonl ]; then
  cp build/evidence/verified-kills.jsonl "$ATLAS_HA_BUNDLE/"
fi
git diff > "$ATLAS_HA_BUNDLE/final-changes.patch"
make ha-down
```

If no report was created, record that fact and preserve the failed startup/build
logs instead. Inspect the bundle before ending the session. Record any cleanup
failure; do not claim stopped containers unless `docker compose -f compose.ha.yaml
ps -a` confirms it.

`ha-down` does not explicitly remove volumes. The image may create anonymous data
volumes; inspect the saved mounts before deciding what to remove. For a clean
disposable reset after evidence is preserved, use the project-scoped command
`docker compose -f compose.ha.yaml down --volumes` while its containers still exist
so Compose can identify their volumes. If already detached, remove only verified
task-owned volume IDs. Never run `docker system prune` or delete unrelated data.
Do not use old data accidentally in a claimed fresh run.

## Troubleshooting and stopping conditions

| Symptom | Next diagnostic / action |
|---|---|
| Docker reports too little memory | Stop before startup; adjust the Linux VM/Docker allocation without starving the host, or use a larger machine |
| No matching image architecture | Preserve manifest output; use a compatible Linux architecture or explicitly document emulation; keep 4.0.5 |
| Port already bound | Identify the owning project/process; do not kill unrelated work |
| Nodes healthy individually but ring incomplete | Inspect every `nodetool status`, bootstrap/gossip logs, seed resolution and resources; preserve failures before targeted restart |
| Driver reports unreachable discovered peers | Check native Docker-bridge routing versus Docker Desktop; co-locate the harness in Linux or test an explicit address translator |
| Wrong replication | You have stale/wrong fixture data or are targeting another cluster; do not drop a keyspace until ownership is verified |
| CAS timeout during failover | Preserve its cause and original request; distinguish unavailable quorum, membership transition and resource starvation; never assume failure means no effect |
| Exit 137 | Inspect OOM state: a planned verified SIGKILL and an OOM kill are different conditions |
| Return from laptop sleep | Mark the run interrupted/confounded; do not report it as a clean controlled-fault experiment |
| Report says green but witness is missing | Mark the HA proof incomplete and repair the witness gate |

## Required handback to the primary agent

Provide a concise result plus the evidence bundle path and a committed task branch
or patch. Do not merge the PR automatically. Include:

* Exact commit, all local changes, host OS/architecture, Docker context, image
  digest, CPU/memory/disk allocation and whether emulation was used.
* Commands, start/end times, exit codes, exact test counts and excluded cases.
* Before/during/after topology, RF, coordinator and kill witnesses; original
  requests, receipts, ambiguous outcomes and recovery attempts where instrumented.
* Every failed attempt and its diagnosis; distinguish observation from inference.
* Whether only the old HA smoke ran or the newer independent contracts also ran.
* Resource pressure, actual cleanup status, remaining gaps and proposed next step.

Suggested pickup instruction for another agent:

> Continue the Atlas Cassandra POC in kverma/codex-playground. Read
> atlas-cassandra-poc/docs/nine-node-ha-agent-handoff.md and applicable AGENTS.md.
> Inspect this laptop's architecture, Docker networking and resources first.
> Use an isolated checkout, preserve Cassandra4.0.5/JDK25/Gradle9.1.0/Make, and do
> not edit RevealSwift. Run the nine-node baseline only if preflight is suitable,
> preserve all evidence, then harden the explicitly listed witness/recovery gaps.
> Do not treat a timeout as definitive failure, weaken checks to get green, or
> claim independent-host/remote-archive durability. Return results, artifacts and
> the task branch; leave the PR unmerged.

No nine-node cluster was started in preparing this guide. Its commands were
reviewed against the repository, not validated on your laptop. Additional work
that can stay remote is ranked in [the Actions roadmap](github-actions-validation-roadmap.md).
