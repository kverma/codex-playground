#!/usr/bin/env bash
set -euo pipefail
compose=(docker compose -f compose.three.yaml)
peer_ip() {
  local container
  container=$("${compose[@]}" ps -q "$1")
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container"
}
probe() {
  "${compose[@]}" exec -T "$1" timeout 2 bash -c "exec 3<>/dev/tcp/$2/7000" >/dev/null 2>&1
}
open_edges() {
  local dc1ip ip
  dc1ip=$(peer_ip dc1)
  for peer in dc2 dc3; do
    ip=$(peer_ip "$peer")
    probe dc1 "$ip" || { echo "Expected dc1 -> $peer reachable" >&2; exit 1; }
    probe "$peer" "$dc1ip" || { echo "Expected $peer -> dc1 reachable" >&2; exit 1; }
  done
}
blocked_edges() {
  local dc1ip ip code
  dc1ip=$(peer_ip dc1)
  for peer in dc2 dc3; do
    ip=$(peer_ip "$peer")
    for origin in dc1 "$peer"; do
      target=$ip; [ "$origin" = dc1 ] || target=$dc1ip
      if probe "$origin" "$target"; then
        echo "Partition ineffective: $origin -> $target connected" >&2; exit 1
      else
        code=$?
        [ "$code" = 124 ] || { echo "Probe failed without verified timeout: $code" >&2; exit 1; }
      fi
      echo "blocked TCP edge $origin -> $target:7000 (timeout)"
    done
  done
}
client_open() {
  timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/9042' || { echo 'Host client port must stay reachable' >&2; exit 1; }
}
verify_counters() {
  local ip column
  for peer in dc2 dc3; do
    ip=$(peer_ip "$peer")
    for direction in OUTPUT INPUT; do
      column=9; [ "$direction" = INPUT ] && column=8
      sudo nsenter -t "$pid" -n iptables -L "$direction" -n -v -x |
        awk -v expected="$ip" -v column="$column" '$3 == "DROP" && $column == expected && /atlas-poc-partition/ {n++; packets += $1} END {exit !(n == 1 && packets > 0)}' || {
          echo "Expected exactly one positive DROP counter: $direction $peer" >&2; exit 1;
        }
    done
  done
  sudo nsenter -t "$pid" -n iptables -L -n -v -x
}
case "${1:-}" in
  up)
    for node in dc1 dc2 dc3; do
      "${compose[@]}" up -d --wait --wait-timeout 300 "$node"
    done
    for attempt in $(seq 1 60); do
      healthy=true
      for node in dc1 dc2 dc3; do
        status=$("${compose[@]}" exec -T "$node" nodetool status 2>&1) || { echo "$status" >&2; exit 1; }
        [ "$(echo "$status" | awk '$1 == "UN" {n++} END {print n+0}')" = 3 ] || healthy=false
      done
      if [ "$healthy" = true ]; then echo "$status"; open_edges; client_open; exit 0; fi
      sleep 2
    done
    echo 'Expected three Up/Normal nodes in every node view' >&2; exit 1 ;;
  partition|heal|counters)
    # Only enter this disposable container's network namespace. Host rules are unchanged.
    id=$("${compose[@]}" ps -q dc1)
    pid=$(docker inspect -f '{{.State.Pid}}' "$id")
    if [ "$1" = partition ]; then open_edges; client_open; fi
    for peer in dc2 dc3; do
      ip=$(peer_ip "$peer")
      for direction in OUTPUT INPUT; do
        flag=-d; [ "$direction" = INPUT ] && flag=-s
        rule=("$direction" "$flag" "$ip" -m comment --comment atlas-poc-partition -j DROP)
        if [ "$1" = partition ]; then
          if sudo nsenter -t "$pid" -n iptables -C "${rule[@]}" 2>/dev/null; then echo 'Existing partition rule: fixture not clean' >&2; exit 1; fi
          sudo nsenter -t "$pid" -n iptables -I "${rule[@]}"
        elif [ "$1" = heal ]; then
          if sudo nsenter -t "$pid" -n iptables -C "${rule[@]}" 2>/dev/null; then sudo nsenter -t "$pid" -n iptables -D "${rule[@]}"; fi
          if sudo nsenter -t "$pid" -n iptables -C "${rule[@]}" 2>/dev/null; then echo 'Partition rule remains after heal' >&2; exit 1; fi
        fi
      done
    done
    if [ "$1" = partition ]; then blocked_edges; client_open; verify_counters; fi
    if [ "$1" = counters ]; then verify_counters; fi
    if [ "$1" = heal ]; then open_edges; client_open; echo 'All peer edges and client port reachable after healing'; fi ;;
  *) echo 'Usage: three.sh up|partition|heal|counters' >&2; exit 2 ;;
esac
