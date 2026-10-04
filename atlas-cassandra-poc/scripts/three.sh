#!/usr/bin/env bash
set -euo pipefail
compose=(docker compose -f compose.three.yaml)
case "${1:-}" in
  up)
    for node in dc1 dc2 dc3; do
      "${compose[@]}" up -d --wait --wait-timeout 300 "$node"
    done
    for attempt in $(seq 1 60); do
      status=$("${compose[@]}" exec -T dc1 nodetool status 2>&1) || { echo "$status" >&2; exit 1; }
      if [ "$(echo "$status" | awk '$1 == "UN" {n++} END {print n+0}')" = 3 ]; then
        echo "$status"; exit 0
      fi
      sleep 2
    done
    echo 'Expected three Up/Normal nodes' >&2; exit 1 ;;
  partition|heal|counters)
    # Only enter this disposable container's network namespace. Host rules are unchanged.
    id=$("${compose[@]}" ps -q dc1)
    pid=$(docker inspect -f '{{.State.Pid}}' "$id")
    for peer in dc2 dc3; do
      peerid=$("${compose[@]}" ps -q "$peer")
      ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$peerid")
      for direction in OUTPUT INPUT; do
        flag=-d; [ "$direction" = INPUT ] && flag=-s
        rule=("$direction" "$flag" "$ip" -m comment --comment atlas-poc-partition -j DROP)
        if [ "$1" = partition ]; then
          sudo nsenter -t "$pid" -n iptables -I "${rule[@]}"
        elif [ "$1" = heal ]; then
          sudo nsenter -t "$pid" -n iptables -D "${rule[@]}"
        fi
      done
    done
    sudo nsenter -t "$pid" -n iptables -L -n -v -x
    if [ "$1" = counters ]; then
      sudo nsenter -t "$pid" -n iptables -L OUTPUT -n -v -x | awk '/atlas-poc-partition/ {packets += $1} END {exit !(packets > 0)}'
    fi ;;
  *) echo 'Usage: three.sh up|partition|heal|counters' >&2; exit 2 ;;
esac
