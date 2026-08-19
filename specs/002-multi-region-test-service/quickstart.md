# Quickstart: Multi-Region Test Scenarios

**Date**: 2026-08-05
**Feature**: `002-multi-region-test-service`

This guide shows how to exercise the multi-region scenarios. Most validation is
operational: deploy to two regions and observe behavior. Topic behavior can be
exercised locally with a broker. Automated tests cover single-region behavior only.

Base URL local: `http://localhost:9000`. Two-region base URLs: the two region
route hostnames from `akka service ... expose`.

---

## A. Local: topic publishing (single region)

Enable a local broker only when exercising topics:

```shell
# Kafka at localhost:9092 (default), or use the Pub/Sub emulator
mvn compile exec:java -Dakka.javasdk.dev-mode.eventing.support=kafka
```

Create records, then read the topic counter:

```shell
curl -X POST http://localhost:9000/pulse/ese/test-1/create \
  -H "Content-Type: application/json" \
  -d '{"name":"my-record","value":"hello","delaySeconds":0}'

curl http://localhost:9000/pulse/topic-counter/synthetic-record-events
# origin-only mode → messageCount increments once per event
```

Switch mode by editing `application.conf` (`pulse.topic.publish-mode = every-region`)
and restarting. In a single region the count is the same; the difference appears only
across regions (see section C).

---

## B. Deploy to two regions

```shell
# Build and push the image
mvn clean install -DskipTests
akka service deploy akka-pulse akka-pulse:tag --push

# Ensure the project has two regions; add one if needed
akka project regions list
akka project regions add gcp-us-east1

# Choose primary selection mode via the service descriptor (service.yaml):
#
#   service:
#     replication:
#       mode: replicated-read
#       replicatedRead:
#         primarySelectionMode: pinned-region   # or request-region (Akka default)
#
akka service apply -f service.yaml

# For pinned-region, set the project primary
akka project regions set-primary gcp-europe-west1
```

Configure the project broker once (for topic scenarios):

```shell
akka projects config set broker --broker-service kafka ...   # or google-pubsub
```

---

## C. Application logic scenarios (AL-*)

| Scenario | Steps |
|----------|-------|
| AL-1 create/read | `POST /pulse/ese/r1/create` on Region A → `GET /pulse/ese/r1` on Region B (expect eventual value) |
| AL-2 update/read | `POST /pulse/ese/r1/update` on A → `GET` on B (expect new value + version) |
| AL-3 rapid writes | Loop updates on A → read on B until final value converges |
| AL-4 KVE set/delete | `POST /pulse/kve/e1` on A → `GET` on B; `DELETE` on A → `GET` on B returns empty |
| AL-5 view from non-primary | `GET /pulse/view/all` on B after creating on A |
| AL-6 workflow start/query | `POST /pulse/workflows/wf1/start` on A → `GET /pulse/workflows/wf1` on B |
| AL-7 workflow compensation | start with `mode=trigger-failure` → status `COMPENSATED` |
| AL-8 consumer counter | create records → `GET /pulse/consumers/synthetic-record-counter` (once per event) |
| AL-9 timed action | `POST /pulse/timers/t1/schedule` → observe the timer fires; check it runs in the expected region with no duplicate execution. **Observation only** — Akka documents no multi-region guarantee for Timed Actions (see plan.md risks) |
| AL-10 validation on non-primary | send an invalid write to B → expect the same error the primary returns |
| AL-11 topic origin-only | create on A → `GET /pulse/topic-counter/...` = events (no duplicates) |
| AL-12 topic every-region | redeploy in `every-region` mode → count = events × regions |
| AL-13 topic subject/metadata | inspect broker message: `ce-subject` = recordId, `ce-origin-region` set |

---

## D. Operational scenarios (OP-*)

```shell
# OP-1 down a region
akka project settings down-region --region gcp-us-east1
# → traffic served by the surviving region

# OP-2 bring it back
akka project settings bring-up-region --region gcp-us-east1
# → replication lag high then ~zero in Control Tower

# OP-3 switch primary (pinned mode)
akka project regions set-primary gcp-us-east1

# OP-10 region identity
curl https://<region-a-host>/pulse/health   # → "region": "<region A>"
curl https://<region-b-host>/pulse/health   # → "region": "<region B>"
```

- **OP-8 replication lag**: observe in the Control Tower replication section (lag =
  time from event creation to receipt in the other region).

---

## E. Dependency & integration scenarios (DI-*)

- **DI-1 consumer restart**: pause/resume the service in a region; the consumer
  resumes from its last position.
- **DI-2 view rebuild**: views rebuild from replicated events after data loss.
- **DI-4 broker unavailable**: stop the broker during publishing; on recovery,
  events are retried, no permanent loss.
- **DI-5 origin region fails before publish**: down the origin region between a write
  and its publish; in `origin-only` mode that one message is not republished
  elsewhere (documented limitation — see `contracts/topic-messaging.md`).

---

## F. Throughput (SC-007)

Drive a spike across both regions using the existing BurstEndpoint and confirm no
degradation and that replication keeps up.

```shell
# Fire 100 parallel operations at the ESE target; repeat against each region host
curl -X POST https://<region-a-host>/pulse/burst/ \
  -H "Content-Type: application/json" \
  -d '{"target":"ese","count":100,"delaySeconds":0}'
```

Confirm the burst summary reports all requests succeeded, and reads from the other
region converge within SC-001 (5s). Hit both region hosts to reach ≥100 ops/sec across regions.

---

## Success criteria checks

| SC | Check |
|----|-------|
| SC-001 | Read on B within 5s of write on A |
| SC-002 | Read latency < 500ms from either region |
| SC-003 | Down a region → surviving region serves within 30s |
| SC-004 | Bring up a downed region (~1000 records) → catches up within 2 min (Control Tower lag → ~0) |
| SC-006 | Consumer counter = one per event |
| SC-007 | ≥100 concurrent ops/sec across both regions via BurstEndpoint, no degradation |
| SC-009 | Topic counter = one per event in origin-only mode (zero duplicates) |
