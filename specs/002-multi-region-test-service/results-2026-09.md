# Results: multi-region assumption probes (2026-09-29)

**Feature**: `002-multi-region-test-service`, User Story 8
**Environment**: Akka SDK 3.6.5 (runtime 1.6.17), regions `aws-us-east-2` and `gcp-us-east1`
**Projects**:

- Project 1: created in `aws-us-east-2`, `gcp-us-east1` added later. Main passes.
- Project 2: created in `gcp-us-east1`, `aws-us-east-2` added later. Clean late-region run.

**Image**: `docker.io/efgpinto/pulse-core:mr-probe-3` (probes plus server-side call timing).
**Scripts**: `scripts/mr-probe.sh`, `scripts/mr-late-region.sh`, `scripts/mr-settle.sh`.

Region A is `aws-us-east-2`, region B is `gcp-us-east1`, unless stated otherwise.
Times marked "server" are the component call time measured inside the endpoint, without the
client round-trip. Client times include about 350 ms of round-trip from the test machine.

## Summary

| ID | Assumption | Result |
|---|---|---|
| H1 | A Consumer of entity events runs in every region | Confirmed. Unguarded side effect ran 3+3 times for 3 events. Guarded with `hasLocalOrigin()`: 3+0. |
| H2 | `hasLocalOrigin()` is true when `originRegion` is empty | Confirmed locally (TestKit). Not tested on a deployment: no broker, and the service-stream probe was not run. |
| H3 | Timers are region-local; a same-name timer scheduled from a Consumer fires in each region | Confirmed. 3+3 unguarded, 3+0 guarded. A timer scheduled once from an endpoint fired once, in the scheduling region. |
| H4 | `request-region`: a write in B moves the primary to B. `pinned-region`: it is forwarded | Confirmed for ESE and KVE. Switch costs about 50-67 ms server time. Forward costs about 42 ms. |
| H5 | A plain `Effect` read in B: switch (request-region) or forward (pinned-region) | Confirmed. Request-region: plain read in B took 51-55 ms (a switch), and the next write in A took 54-57 ms (switch back). `ReadOnlyEffect` read took 1-2 ms. Pinned: plain read forwarded to A (39-42 ms). |
| H6 | A Workflow keeps its primary in the creating region, also under `request-region` | Confirmed in both modes. A signal sent to B was handled in A (66-203 ms server time). The pause timeout fired in A. |
| H7 | `applyEvent` runs in each region; region-local values diverge | Confirmed. `writtenAt` (from the event) equal; `appliedAt` and `appliedIn` differ per region. |
| H8 | Each region builds its own View | Confirmed. `builtIn` and `viewUpdatedAt` differ per region; `hasLocalOrigin` is true in A and false in B. |
| H9 | A self-region replication filter keeps state and View rows out of other regions | Confirmed for ESE and KVE. B reads empty state; B View has no row. |
| H10 | Concurrent writes to one entity from two regions | Request-region: 23 of 40 writes failed (HTTP 500 after about 5.4 s). Pinned: 0 of 40. Separate entities: 0 of 40 in both modes. |
| H11 | Replication lag A to B | About 60-100 ms above a local read (client times 409-646 ms vs about 350 ms round-trip). |
| H13 | A region added to a project with running services and data | See section H13. Existing state, Views and workflows reach the new region. The new region's Consumers process the full event history. |
| H14 | Entity creation during a deploy, restart or region add | Request-region: first writes to new entities fail in all regions for minutes. Pinned: only the restarting non-primary region is affected, for about 1 minute. See section H14. |
| H12 | Pending timers in a downed region | Not run (`down-region` not exercised). Timer locality is covered by H3 and H13. |

## H13: late region

Two runs. Run r1 added `gcp-us-east1` to project 1. Run r2 added `aws-us-east-2` to project 2,
with a per-event "seen" ledger in the Consumer.

| Probe | Observed |
|---|---|
| Rolling update of existing services | Adding the region rolled the stateful service in the existing region. |
| Routes | A route created while the project had one region keeps that region's hostname. It syncs to the new region as `NotConfigured`. `akka services expose --region <new>` fails with "route already exists". A second, regional route (`akka routes create <name> --hostname <generated host in new region> --region <new>`) works. |
| New region readiness | The new region reported Ready about 50 s after the add, before its data arrived. For about 40 s after Ready it served reads with no data: entity reads returned empty state, the View had no rows, and workflow status returned "not found" (HTTP 400). |
| ESE and KVE state | Replicated to the new region, including events written before the add. |
| Replication filter set before the add | Not replicated to the new region (the filter listed only the original region). |
| View | Rebuilt in the new region from the full history. |
| Consumer | The new region's Consumer processed every event written before the region existed. r2: each of the 3 seed events was seen exactly once in the new region. Unguarded side effects fire again for the whole history; the `hasLocalOrigin()` guard filters them. |
| Workflow | State replicated. A signal sent to the new region was forwarded to the creating region. The pause timeout fired in the creating region. |
| Timer | A timer scheduled 15 minutes before the add fired once, in the original region. It was not copied. |
| Entity creation after the add | The existing region is affected too. r2: in the original region, first writes to new entities failed intermittently from 15:01:02 to 15:03:16 (11 of 76 loop rounds), starting about 20 s after the add. r1: failures for several minutes (see H14). Writes to existing entities were not affected. |

## H14: entity creation during deploys and restarts

`scripts/mr-settle.sh` and a 1-second write loop (new entity id and existing entity id, per region).

| Mode | Event | Observed |
|---|---|---|
| request-region, 1 instance | Rolling restart (applied to both regions) | 14:01:33-14:04:36: new-entity writes failed in both regions (HTTP 500 after 5.4 s). Writes to an existing entity whose primary is local: 0 failures. |
| request-region, 2 instances | Rollout of a new image | New-entity writes failed for about 4 minutes after both regions reported Ready. Right after Ready: 70-80% failed. |
| request-region, 2 instances | Rolling restart | 14:15:31-14:18:42: intermittent new-entity failures in both regions. Existing entities: 0 failures. |
| pinned-region, 2 instances | Rolling restart | Primary region (A): 0 failures over 10 minutes. B: timeouts for about 1 minute while its own pods restarted. |

What the caller sees and why: under `request-region`, the first write to a new entity, and every
write that moves an entity's primary, waits for an acknowledgement from every other region. If the
acknowledgement does not arrive within 5 seconds, the write fails with a generic
`Unexpected error [<correlation id>]` (HTTP 500) and is not retried. A service cannot change this
timeout. The service logs do not show the cause. Writes to an entity whose primary is already the
local region do not wait, which is why they kept working.

## OP: operational observations

- **OP-A**: `akka services restart --region <r>` restarted all regions ("This operation will be
  synced to all regions").
- **OP-B**: in `none` mode, writes fail with the same generic `Unexpected error` (HTTP 500). The
  error does not say the service is read-only. Rolling out `none` took about 7 minutes.
- **OP-C**: `akka projects regions remove` has no non-interactive confirmation flag; the prompt
  defaults to "No".

## Raw data

`results/<run-id>/` (not committed): `rr-1` (before settling, discarded), `rr-2`, `rr-3`
(request-region, with server times), `pin-1` (pinned-region), `late-region-r1`, `late-region-r2`.
