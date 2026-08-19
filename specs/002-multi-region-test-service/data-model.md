# Data Model: Multi-Region Test Scenarios

**Date**: 2026-08-05
**Feature**: `002-multi-region-test-service`

This feature reuses the existing synthetic domain model. Only the additions and
changes for multi-region topic publishing and region identity are listed here.
For the existing model, see `specs/001-synthetic-tests/data-model.md`.

## Legend

- **existing** — already implemented, unchanged.
- **changed** — an existing type gains a field or behavior.
- **new** — added by this feature.

---

## Reused, unchanged (existing)

| Type | Kind | Notes |
|------|------|-------|
| SyntheticRecord | ESE state | Source of the events the producer publishes |
| SyntheticRecordEvent (RecordCreated, RecordUpdated) | ESE events | `@TypeName` present; replicated across regions |
| SyntheticEntry | KVE state | Replicated; empty state replicates on delete |
| SyntheticWorkflowState | Workflow state | Durable; resumes in failover region |
| SyntheticRecordView (SyntheticRecordEntry) | View | Materialized per region |
| ConsumerCounter | KVE state | Existing consumer counter; unchanged |

---

## Changed

### HealthCheckEntry (Key Value Entity state) — *changed*

Gains a `region` field so the health endpoint can report region identity (FR-014).

| Field | Type | Description |
|-------|------|-------------|
| timestamp | Instant | When the heartbeat was written |
| status | String | Always "OK" when written |
| **region** | **String** | **Self region, read via `commandContext().selfRegion()`** |

`HealthCheckEntity.set()` reads `commandContext().selfRegion()` and stores it.
`HealthCheckEntity.get()` returns it. The endpoint surfaces it in the health response.

---

## New

### SyntheticTopicMessage (topic message payload) — *new*

The message published to the `synthetic-record-events` topic. Defined in `domain`.
Carries a `@TypeName` so the downstream consumer can match it (CloudEvents `ce-type`).

| Field | Type | Description |
|-------|------|-------------|
| recordId | String | Originating record ID (also set as `ce-subject`) |
| eventType | String | "record-created" or "record-updated" |
| value | String | Record value at the time of the event |
| originRegion | String | Region where the event first occurred |

Message metadata attached on produce:
`Metadata.EMPTY.add("ce-subject", recordId).add("ce-origin-region", originRegion)`.

---

### TopicMessageCounter (Key Value Entity state) — *new*

Tracks messages read from the topic by the downstream consumer, so duplicate
publishing is observable (FR-010, SC-009).

| Field | Type | Description |
|-------|------|-------------|
| counterId | String | Unique identifier (entity ID) |
| messageCount | long | Total messages read from the topic |
| lastOriginRegion | String | Origin region of the most recent message |
| lastMessageAt | Instant | Timestamp of the most recent message |

**State transitions**:
- `null` → count 1 (first message read)
- count N → count N+1 (each subsequent message)

`increment(originRegion, at)` returns a new instance with `messageCount + 1`.

---

## Component additions and relationships

```
SyntheticRecordEntity ──events (replicated per region)──▶ SyntheticTopicProducer
    SyntheticTopicProducer:
      origin-only mode  → publishes only when hasLocalOrigin()  → exactly one message
      every-region mode → publishes in every region             → one message per region
                         │
                         ▼  @Produce.ToTopic("synthetic-record-events")
                   [ topic ]
                         │  @Consume.FromTopic("synthetic-record-events")
                         ▼
              SyntheticTopicConsumer ──writes──▶ TopicMessageCounterEntity (KVE)

HealthEndpoint ──write/read──▶ HealthCheckEntity (KVE, reads commandContext().selfRegion())
```

## Configuration

| Key | Location | Values | Purpose |
|-----|----------|--------|---------|
| `pulse.topic.publish-mode` | `application.conf` | `origin-only` (default), `every-region` | Build-time producer mode (FR-009) |
| `akka.javasdk.dev-mode.eventing.support` | `application.conf` / system prop | `none` (default), `kafka`, `google-pubsub-emulator` | Local broker for topic runs (research §5) |
| `service.replication.replicatedRead.primarySelectionMode` | service descriptor | `pinned-region`, `request-region` | Deploy-time primary selection (FR-012) |
