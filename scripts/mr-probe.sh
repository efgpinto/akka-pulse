#!/usr/bin/env bash
# Multi-region assumption probes (spec 002, US8). Runs H1-H11 against two region hosts of
# pulse-core and prints one result row per hypothesis. Raw responses go to results/<run-id>/.
#
# Usage:
#   scripts/mr-probe.sh <url-region-a> <url-region-b> [run-id]
#   scripts/mr-probe.sh http://localhost:9000 http://localhost:9000     # single-region smoke run
#
# Optional environment:
#   PEER_A, PEER_B   pulse-peer base URLs per region, to read the H2 stream counters
#   SETTLE           seconds to wait for replication and timers (default 15)
#   H10_WRITES       writes per region in the contention test (default 20)
#
# The script writes to fresh entity ids per run, so it can be rerun against the same deployment.

set -euo pipefail

A=${1:?url of region A}
B=${2:?url of region B}
RUN=${3:-$(date +%Y%m%d-%H%M%S)}
SETTLE=${SETTLE:-15}
H10_WRITES=${H10_WRITES:-20}

OUT="results/$RUN"
mkdir -p "$OUT"
RAW="$OUT/raw.log"
TABLE="$OUT/results.md"
: > "$RAW"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

# req METHOD URL [JSON]  ->  sets RESP, CODE, SECS
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
ms() { awk -v s="$1" 'BEGIN { printf "%d", s * 1000 }'; }
# srv: server-side component call time of the last response, falling back to the client time
srv() { local c; c=$(field .callMicros); if [[ "$c" == "-" ]]; then echo "client $(ms "$SECS")ms"; else echo "$((c / 1000))ms"; fi; }

row() { printf '| %s | %s | %s | %s |\n' "$1" "$2" "$3" "$4" | tee -a "$TABLE"; }

ledger() { # ledger BASE ID -> count
  req GET "$1/pulse/probe/ledger/$2"
  field .count
}

write_ese() { req POST "$1/pulse/probe/ese/$2" "{\"value\":\"$3\",\"withTimer\":${4:-false}}"; }

req GET "$A/pulse/probe/whoami"; RA=$(field .region)
req GET "$B/pulse/probe/whoami"; RB=$(field .region)

{
  echo "# Multi-region probe run $RUN"
  echo
  echo "- Region A: \`$RA\` ($A)"
  echo "- Region B: \`$RB\` ($B)"
  echo
  echo "| ID | Probe | Observed | Verdict |"
  echo "|---|---|---|---|"
} > "$TABLE"
cat "$TABLE"

[[ "$RA" == "$RB" ]] && echo "WARNING: both URLs report region $RA; cross-region results are not meaningful" >&2

# --- H4: where does a write to an entity with primary A run when sent to B? ---
for kind in ese kve; do
  id="h4-$kind-$RUN"
  req POST "$A/pulse/probe/$kind/$id" '{"value":"a1","withTimer":false}'; first=$(field .handledIn); t1=$(srv)
  sleep 3
  req POST "$B/pulse/probe/$kind/$id" '{"value":"b1","withTimer":false}'; second=$(field .handledIn); t2=$(srv)
  req POST "$A/pulse/probe/$kind/$id" '{"value":"a2","withTimer":false}'; third=$(field .handledIn); t3=$(srv)
  verdict="primary switched to B (request-region)"
  [[ "$second" == "$RA" ]] && verdict="forwarded to A"
  [[ "$second" != "$RA" && "$second" != "$RB" ]] && verdict="forwarded to $second"
  row "H4-$kind" "write A, then B, then A" "handledIn (server call time): $first ($t1), $second ($t2), $third ($t3)" "$verdict"
done

# --- H5: plain Effect read vs ReadOnlyEffect read on B ---
for kind in ese kve; do
  id="h5-$kind-$RUN"
  req POST "$A/pulse/probe/$kind/$id" '{"value":"a1","withTimer":false}'
  sleep 3
  req GET "$B/pulse/probe/$kind/$id/read-only"; ro=$(field .handledIn); ro_seq=$(field .state.seq); t1=$(srv)
  req GET "$B/pulse/probe/$kind/$id/plain"; pl=$(field .handledIn); pl_seq=$(field .state.seq); t2=$(srv)
  req POST "$A/pulse/probe/$kind/$id" '{"value":"a2","withTimer":false}'; after=$(field .handledIn); t3=$(srv)
  row "H5-$kind" "read-only on B, plain on B, then write on A" \
    "read-only: $ro seq=$ro_seq ($t1); plain: $pl seq=$pl_seq ($t2); next write on A: $after ($t3)" \
    "$([[ "$pl" == "$RB" ]] && echo "plain read ran on B" || echo "plain read ran on $pl")"
done

# --- H11: replication lag, write on A then poll read-only on B ---
lags=(); failed=0
for i in $(seq 1 10); do
  id="h11-$RUN-$i"
  write_ese "$A" "$id" "v"
  if [[ "$CODE" != 200 ]]; then failed=$((failed + 1)); continue; fi
  start=$(date +%s%N)
  seen=no
  for _ in $(seq 1 100); do
    req GET "$B/pulse/probe/ese/$id/read-only"
    if [[ "$(field .state.seq)" == "1" ]]; then seen=yes; break; fi
    sleep 0.05
  done
  end=$(date +%s%N)
  if [[ $seen == yes ]]; then lags+=($(( (end - start) / 1000000 ))); else lags+=("never"); fi
done
sorted=$(printf '%s\n' "${lags[@]}" | sort -n | tr '\n' ' ')
row "H11" "write on A, poll read-only on B until seen (10 runs, includes client round-trip)" \
  "lag ms (sorted): $sorted; failed writes: $failed" "-"

# --- H1, H3: consumer and timer side effects ---
declare -A before
for probe in consumer-unguarded consumer-guarded timer-unguarded timer-guarded timer-endpoint; do
  for r in "$RA" "$RB"; do before["$probe-$r"]=$(ledger "$A" "$probe-$r"); done
done
id="h1-$RUN"
for v in 1 2 3; do write_ese "$A" "$id" "v$v" true; done
req POST "$A/pulse/probe/timer/h3-endpoint-$RUN" '{"delaySeconds":5}'
echo "waiting ${SETTLE}s for consumers and timers" >&2
sleep "$SETTLE"
delta() { local now; now=$(ledger "$A" "$1-$2"); echo $(( now - ${before["$1-$2"]} )); }
row "H1" "3 writes on A; consumer side effect per region (unguarded / guarded)" \
  "unguarded A=$(delta consumer-unguarded "$RA") B=$(delta consumer-unguarded "$RB"); guarded A=$(delta consumer-guarded "$RA") B=$(delta consumer-guarded "$RB")" \
  "expect unguarded 3+3, guarded 3+0"
req GET "$A/pulse/probe/ledger/consumer-unguarded-$RB"
row "H1-meta" "what the consumer in B saw" "originRegion=$(field .lastOriginRegion) hasLocalOrigin=$(field .lastHasLocalOrigin)" "-"
row "H3" "same-name timer scheduled by the consumer in each region (unguarded / guarded)" \
  "unguarded A=$(delta timer-unguarded "$RA") B=$(delta timer-unguarded "$RB"); guarded A=$(delta timer-guarded "$RA") B=$(delta timer-guarded "$RB")" \
  "expect unguarded 3+3, guarded 3+0"
row "H3-baseline" "timer scheduled once from the endpoint on A" \
  "fired A=$(delta timer-endpoint "$RA") B=$(delta timer-endpoint "$RB")" "expect 1+0"

# --- H7: applyEvent determinism ---
req GET "$A/pulse/probe/ese/$id/read-only"; wa=$(field .state.writtenAt); aa=$(field .state.appliedAt); ai=$(field .state.appliedIn)
req GET "$B/pulse/probe/ese/$id/read-only"; wb=$(field .state.writtenAt); ab=$(field .state.appliedAt); bi=$(field .state.appliedIn)
row "H7" "event field vs value computed in applyEvent, read in A and B" \
  "writtenAt equal: $([[ "$wa" == "$wb" ]] && echo yes || echo no); appliedAt A=$aa B=$ab; appliedIn A=$ai B=$bi" \
  "$([[ "$aa" != "$ab" ]] && echo "applyEvent values diverge" || echo "no divergence seen")"

# --- H8: each region builds its own View ---
req GET "$A/pulse/probe/view/$id"; va=$(field '.entries[0].builtIn'); ua=$(field '.entries[0].viewUpdatedAt'); la=$(field '.entries[0].hasLocalOrigin'); sa=$(field '.entries[0].seq')
req GET "$B/pulse/probe/view/$id"; vb=$(field '.entries[0].builtIn'); ub=$(field '.entries[0].viewUpdatedAt'); lb=$(field '.entries[0].hasLocalOrigin'); sb=$(field '.entries[0].seq')
row "H8" "View row for the same entity in A and B" \
  "builtIn A=$va B=$vb; seq A=$sa B=$sb; hasLocalOrigin A=$la B=$lb; viewUpdatedAt A=$ua B=$ub" \
  "$([[ "$va" != "$vb" ]] && echo "each region builds its own View" || echo "same builder")"

# --- H9: replication filter ---
for kind in filtered-ese filtered-kve; do
  fid="h9-$kind-$RUN"
  req POST "$A/pulse/probe/$kind/$fid" '{"value":"secret","withTimer":false}'; w=$(field .handledIn)
  sleep 5
  req GET "$A/pulse/probe/$kind/$fid/read-only"; ra=$(field .state.value)
  req GET "$B/pulse/probe/$kind/$fid/read-only"; rb=$(field .state.value); cb=$CODE
  extra=""
  if [[ $kind == filtered-ese ]]; then
    req GET "$B/pulse/probe/filtered-view/$fid"; extra="; view on B rows=$(jq '.entries | length' <<< "$RESP" 2>/dev/null || echo "-")"
  fi
  row "H9-$kind" "self-region filter set on first write in A; read-only on A and B" \
    "written in $w; A=$ra; B=$rb (HTTP $cb)$extra" \
    "$([[ "$rb" == "-" || "$rb" == "null" ]] && echo "not replicated to B" || echo "replicated to B")"
done

# --- H6: workflow primary ---
wid="h6-$RUN"
req POST "$A/pulse/probe/workflow/$wid/start" '{"pauseSeconds":600}'; started=$(field .handledIn)
for _ in $(seq 1 50); do
  req GET "$A/pulse/probe/workflow/$wid"; [[ "$(field .state.status)" == "PAUSED" ]] && break; sleep 0.2
done
sleep 3
req POST "$B/pulse/probe/workflow/$wid/signal"; sig=$(field .handledIn); tsig=$(srv); csig=$CODE
req GET "$B/pulse/probe/workflow/$wid"; st=$(field .handledIn); step=$(field .state.stepRanIn)
row "H6" "workflow started on A, signal (write) sent to B" \
  "started in $started; step ran in $step; signal handledIn $sig (HTTP $csig, $tsig); status read on B handled in $st" \
  "$([[ "$sig" == "$RA" ]] && echo "forwarded to creating region" || echo "ran in $sig")"

# --- H10: concurrent writes to one entity from both regions vs per-region entities ---
burst() { # burst BASE ID N FILE
  for i in $(seq 1 "$3"); do
    local body code
    body=$(curl -sS -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' \
      -d "{\"value\":\"$i\",\"withTimer\":false}" "$1/pulse/probe/ese/$2") || body=$'\n000'
    code=${body##*$'\n'}
    micros=$(jq -r '.callMicros // 0' <<< "${body%$'\n'*}" 2>/dev/null || echo 0)
    echo "$code $(awk -v m="$micros" 'BEGIN { printf "%.6f", m / 1000000 }')" >> "$4"
  done
}
stats() { awk '{ print $2 }' "$1" | sort -n | awk '{ a[NR] = $1 } END { printf "p50=%dms max=%dms n=%d", a[int(NR/2)+1]*1000, a[NR]*1000, NR }'; }
: > "$OUT/h10-shared-a"; : > "$OUT/h10-shared-b"; : > "$OUT/h10-own-a"; : > "$OUT/h10-own-b"
burst "$A" "h10-own-a-$RUN" "$H10_WRITES" "$OUT/h10-own-a" & burst "$B" "h10-own-b-$RUN" "$H10_WRITES" "$OUT/h10-own-b" & wait
burst "$A" "h10-shared-$RUN" "$H10_WRITES" "$OUT/h10-shared-a" & burst "$B" "h10-shared-$RUN" "$H10_WRITES" "$OUT/h10-shared-b" & wait
row "H10" "$H10_WRITES writes per region at once: own entity vs one shared entity (server call time; failures count as 0)" \
  "own: A $(stats "$OUT/h10-own-a"), B $(stats "$OUT/h10-own-b"); shared: A $(stats "$OUT/h10-shared-a"), B $(stats "$OUT/h10-shared-b")" \
  "non-200: shared $(cat "$OUT/h10-shared-a" "$OUT/h10-shared-b" | grep -vc '^200'), own $(cat "$OUT/h10-own-a" "$OUT/h10-own-b" | grep -vc '^200')"

# --- H2: stream consumers in pulse-peer (optional) ---
if [[ -n "${PEER_A:-}" && -n "${PEER_B:-}" ]]; then
  for p in "$PEER_A" "$PEER_B"; do
    for r in "$RA" "$RB"; do
      for o in local remote; do
        req GET "$p/peer/probes/stream/stream-$r-$o"; echo "stream-$r-$o via $p: $(field .count)" >> "$OUT/h2-stream"
      done
    done
  done
  row "H2-stream" "service-stream consumer: count per consuming region, by hasLocalOrigin" "$(sort -u "$OUT/h2-stream" | tr '\n' ';')" "expect only *-local counters"
fi
req GET "$A/pulse/probe/ledger/topic-$RA"; ta=$(field .count); tla=$(field .lastHasLocalOrigin)
req GET "$A/pulse/probe/ledger/topic-$RB"; tb=$(field .count); tlb=$(field .lastHasLocalOrigin)
row "H2-topic" "topic consumer ledger (needs broker and pulse.topic.enabled)" "A count=$ta local=$tla; B count=$tb local=$tlb" "-"

rm -f "$OUT/.body"
echo
echo "Results: $TABLE"
echo "Raw responses: $RAW"
