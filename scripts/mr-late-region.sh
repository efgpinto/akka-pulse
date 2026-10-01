#!/usr/bin/env bash
# Late-region probe (spec 002, US8, H13): what a region added to a project with running services
# and existing data receives, and what it does with it.
#
# Usage:
#   scripts/mr-late-region.sh seed  <url-region-a> <run-id>                   # before adding region B
#   akka projects regions add <region-b>                                      # then deploy/expose in B
#   scripts/mr-late-region.sh check <url-region-a> <url-region-b> <run-id>    # after B is ready
#
# seed writes entities, starts a paused workflow and schedules a long timer in region A, and saves
# the ledger counts. check reads everything from both regions and prints one row per probe.

set -euo pipefail

MODE=${1:?seed or check}
A=${2:?url of region A}
if [[ $MODE == check ]]; then B=${3:?url of region B}; RUN=${4:?run id}; else RUN=${3:?run id}; fi

OUT="results/late-region-$RUN"
mkdir -p "$OUT"
RAW="$OUT/raw-$MODE.log"
TIMER_DELAY=${TIMER_DELAY:-900}
WF_PAUSE=${WF_PAUSE:-900}

req() {
  local args=(-sS -o "$OUT/.body" -w '%{http_code} %{time_total}' -X "$1")
  if [[ $# -ge 3 ]]; then args+=(-H 'Content-Type: application/json' -d "$3"); fi
  local meta
  meta=$(curl "${args[@]}" "$2") || meta="000 0"
  RESP=$(cat "$OUT/.body" 2>/dev/null || true)
  CODE=${meta%% *}
  SECS=${meta##* }
  printf '### %s %s -> %s (%ss)\n%s\n\n' "$1" "$2" "$CODE" "$SECS" "$RESP" >> "$RAW"
}
field() { jq -r "$1 | if . == null then \"-\" else . end" <<< "$RESP" 2>/dev/null || echo "-"; }
row() { printf '| %s | %s | %s |\n' "$1" "$2" "$3" | tee -a "$OUT/results.md"; }

req GET "$A/pulse/probe/whoami"; RA=$(field .region)

if [[ $MODE == seed ]]; then
  for i in 1 2 3; do
    req POST "$A/pulse/probe/ese/h13-ese-$RUN" "{\"value\":\"v$i\",\"withTimer\":false}"
  done
  req POST "$A/pulse/probe/kve/h13-kve-$RUN" '{"value":"v1","withTimer":false}'
  req POST "$A/pulse/probe/filtered-ese/h13-filtered-$RUN" '{"value":"v1","withTimer":false}'
  req POST "$A/pulse/probe/workflow/h13-wf-$RUN/start" "{\"pauseSeconds\":$WF_PAUSE}"
  req POST "$A/pulse/probe/timer/h13-timer-$RUN" "{\"delaySeconds\":$TIMER_DELAY}"
  sleep 5
  for l in consumer-unguarded consumer-guarded timer-endpoint; do
    req GET "$A/pulse/probe/ledger/$l-$RA"; echo "$l-$RA=$(field .count)" >> "$OUT/seed-ledgers"
  done
  date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT/seed-time"
  echo "Seeded in $RA at $(cat "$OUT/seed-time"). Timer fires and workflow pause ends ~${TIMER_DELAY}s later."
  cat "$OUT/seed-ledgers"
  exit 0
fi

req GET "$B/pulse/probe/whoami"; RB=$(field .region)
{
  echo "# Late-region run $RUN"
  echo
  echo "- Region A (existing): \`$RA\`; region B (added): \`$RB\`; seeded at $(cat "$OUT/seed-time" 2>/dev/null || echo "?")"
  echo
  echo "| Probe | Observed | Note |"
  echo "|---|---|---|"
} > "$OUT/results.md"
cat "$OUT/results.md"

req GET "$B/pulse/probe/ese/h13-ese-$RUN/read-only"
row "ESE state in B" "seq=$(field .state.seq) value=$(field .state.value) appliedIn=$(field .state.appliedIn) (HTTP $CODE)" "pre-existing events replicated?"
req GET "$B/pulse/probe/kve/h13-kve-$RUN/read-only"
row "KVE state in B" "seq=$(field .state.seq) value=$(field .state.value) (HTTP $CODE)" "pre-existing state replicated?"
req GET "$B/pulse/probe/filtered-ese/h13-filtered-$RUN/read-only"
row "Filtered ESE in B" "value=$(field .state.value) (HTTP $CODE)" "filter set before B existed"
req GET "$B/pulse/probe/view/h13-ese-$RUN"
row "View row in B" "rows=$(jq '.entries | length' <<< "$RESP" 2>/dev/null || echo -) seq=$(field '.entries[0].seq') builtIn=$(field '.entries[0].builtIn') hasLocalOrigin=$(field '.entries[0].hasLocalOrigin')" "View backfilled from history?"
for l in consumer-unguarded consumer-guarded; do
  req GET "$A/pulse/probe/ledger/$l-$RB"
  row "Ledger $l-$RB" "count=$(field .count) lastOrigin=$(field .lastOriginRegion) lastHasLocalOrigin=$(field .lastHasLocalOrigin)" "consumer in B replayed history? (3 events seeded)"
done
seen=""
for seq in 1 2 3; do
  req GET "$B/pulse/probe/ledger/seen-$RB-h13-ese-$RUN-$seq"; seen="$seen seq$seq=$(field .count)"
done
row "Seed events processed by the consumer in B" "$seen" "count 1 = the new region's consumer handled an event written before it existed"
req GET "$B/pulse/probe/workflow/h13-wf-$RUN"
row "Workflow in B" "status=$(field .state.status) startedIn=$(field .state.startedIn) (HTTP $CODE)" "state replicated?"
req POST "$B/pulse/probe/workflow/h13-wf-$RUN/signal"
row "Workflow signal to B" "handledIn=$(field .handledIn) (HTTP $CODE, ${SECS}s)" "forwarded to creating region?"
for r in "$RA" "$RB"; do
  req GET "$A/pulse/probe/ledger/timer-endpoint-$r"
  row "Ledger timer-endpoint-$r" "count=$(field .count) last=$(field .lastAt)" "long timer from seed fires where? (compare to seed-ledgers)"
done
req POST "$B/pulse/probe/ese/h13-ese-$RUN" '{"value":"from-b","withTimer":false}'
row "First write in B" "handledIn=$(field .handledIn) seq=$(field .state.seq) (HTTP $CODE, ${SECS}s)" "request-region switch after late add"
echo "seed ledgers:"; cat "$OUT/seed-ledgers" 2>/dev/null || true
rm -f "$OUT/.body"
