#!/usr/bin/env bash
# Waits until N consecutive first writes to new entities succeed in every given region, and prints
# how long that took. Under request-region, these writes fail for minutes after a deploy, restart
# or region add (spec 002, US8, H14).
#
# Usage: scripts/mr-settle.sh <n> <url>...
set -uo pipefail
N=${1:?consecutive successes}; shift
start=$(date +%s); ok=0; fails=0
while (( ok < N )); do
  good=1
  for h in "$@"; do
    code=$(curl -sS -o /dev/null -m 10 -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
      -d '{"value":"settle","withTimer":false}' "$h/pulse/probe/ese/settle-$RANDOM$RANDOM") || code=000
    [[ $code == 200 ]] || good=0
  done
  if (( good )); then ok=$((ok + 1)); else ok=0; fails=$((fails + 1)); fi
done
echo "settled after $(( $(date +%s) - start ))s with $fails failed rounds ($(date -u +%H:%M:%S) UTC)"
