#!/usr/bin/env bash
# Workflow failover probe (spec 002, US8, H12): what happens to workflows when their primary
# region is marked down.
#
# Usage:
#   scripts/mr-failover.sh setup <url-region-a> <run-id>
#   akka project settings down-region <region-a> <region-b>      # operator step, run by hand
#   scripts/mr-failover.sh check <url-region-a> <url-region-b> <run-id>
#   akka project settings bring-up-region <region-a>              # restore afterwards
#
# setup starts two workflows in region A:
#   h12-paused-<run>   step runs at once, then the workflow pauses for PAUSE seconds
#   h12-running-<run>  step stays in flight for STEP_DELAY seconds, then the workflow pauses
# Mark region A down while h12-running is still in its step.
#
# check reads both workflows from region B, signals the paused one there, and reads the step
# ledgers (wf-step-<workflowId>-<region>). Each count is one execution of the step, which stands
# in for a third-party call.

set -euo pipefail

MODE=${1:?setup or check}
A=${2:?url of region A}
if [[ $MODE == check ]]; then B=${3:?url of region B}; RUN=${4:?run id}; else RUN=${3:?run id}; fi
PAUSE=${PAUSE:-900}
STEP_DELAY=${STEP_DELAY:-240}

OUT="results/failover-$RUN"
mkdir -p "$OUT"
RAW="$OUT/raw-$MODE.log"

req() {
  local args=(-sS -m 30 -o "$OUT/.body" -w '%{http_code} %{time_total}' -X "$1")
  if [[ $# -ge 3 ]]; then args+=(-H 'Content-Type: application/json' -d "$3"); fi
  local meta
  meta=$(curl "${args[@]}" "$2") || meta="000 0"
  RESP=$(cat "$OUT/.body" 2>/dev/null || true)
  CODE=${meta%% *}
  SECS=${meta##* }
  printf '### %s %s -> %s (%ss)\n%s\n\n' "$1" "$2" "$CODE" "$SECS" "$RESP" >> "$RAW"
}
field() { jq -r "$1 | if . == null then \"-\" else . end" <<< "$RESP" 2>/dev/null || echo "-"; }
row() { printf '| %s | %s |\n' "$1" "$2" | tee -a "$OUT/results.md"; }

req GET "$A/pulse/probe/whoami"; RA=$(field .region)

if [[ $MODE == setup ]]; then
  req POST "$A/pulse/probe/workflow/h12-paused-$RUN/start" "{\"pauseSeconds\":$PAUSE,\"stepDelaySeconds\":0}"
  echo "h12-paused-$RUN started in $(field .handledIn) (HTTP $CODE)"
  req POST "$A/pulse/probe/workflow/h12-running-$RUN/start" "{\"pauseSeconds\":$PAUSE,\"stepDelaySeconds\":$STEP_DELAY}"
  echo "h12-running-$RUN started in $(field .handledIn) (HTTP $CODE)"
  date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/setup-time"
  echo
  echo "Within ${STEP_DELAY}s, mark $RA down in the other region, for example:"
  echo "  akka project settings down-region $RA <region-b>"
  exit 0
fi

req GET "$B/pulse/probe/whoami"; RB=$(field .region)
{
  echo "# Workflow failover run $RUN"
  echo
  echo "- Region A (downed): \`$RA\`; region B (survivor): \`$RB\`; setup at $(cat "$OUT/setup-time" 2>/dev/null || echo "?")"
  echo
  echo "| Probe | Observed |"
  echo "|---|---|"
} > "$OUT/results.md"
cat "$OUT/results.md"

for wf in running paused; do
  id="h12-$wf-$RUN"
  req GET "$B/pulse/probe/workflow/$id"
  row "$id status in B" "status=$(field .state.status) startedIn=$(field .state.startedIn) stepRanIn=$(field .state.stepRanIn) timeoutFiredIn=$(field .state.timeoutFiredIn) (HTTP $CODE)"
  counts=""
  for r in "$RA" "$RB"; do
    req GET "$B/pulse/probe/ledger/wf-step-$id-$r"; counts="$counts $r=$(field .count)"
  done
  row "$id step executions (read in B)" "$counts"
done
req POST "$B/pulse/probe/workflow/h12-paused-$RUN/signal"
row "Signal h12-paused in B" "handledIn=$(field .handledIn) (HTTP $CODE, ${SECS}s)"
rm -f "$OUT/.body"
