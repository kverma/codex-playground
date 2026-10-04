#!/usr/bin/env bash
set -euo pipefail
# Start sequentially to avoid concurrent bootstrap/ring-discovery races.
for node in dc1n1 dc1n2 dc1n3 dc2n1 dc2n2 dc2n3 dc3n1 dc3n2 dc3n3; do
  docker compose -f compose.ha.yaml up -d --wait --wait-timeout 300 "$node"
done
for attempt in $(seq 1 30); do
  status=$(docker compose -f compose.ha.yaml exec -T dc1n1 nodetool status)
  if [ "$(echo "$status" | awk '$1 == "UN" {n++} END {print n+0}')" = 9 ]; then
    echo "$status"
    exit 0
  fi
  sleep 2
done
echo 'Expected nine Up/Normal nodes' >&2
exit 1
