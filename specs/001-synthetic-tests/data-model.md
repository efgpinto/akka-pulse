# Data Model: Synthetic Test Suite & Health Monitoring

**Date**: 2026-06-07

## Entities

### SyntheticRecord (Event Sourced Entity)

State representing a synthetic test record with event history.

| Field          | Type    | Description                              |
|----------------|---------|------------------------------------------|
| recordId       | String  | Unique identifier (entity ID)            |
| name           | String  | Test record name                         |
| value          | String  | Arbitrary test value                     |
| status         | String  | Current status (CREATED, UPDATED)        |
| version        | int     | Monotonically increasing version counter |
| lastUpdated    | Instant | Timestamp of last modification           |

**Events** (sealed interface `SyntheticRecordEvent`):

| Event           | TypeName           | Fields                                      |
|-----------------|--------------------|---------------------------------------------|
| RecordCreated   | "record-created"   | name, value, createdAt                      |
| RecordUpdated   | "record-updated"   | value, version, updatedAt                   |

**State transitions**:
- `null` → `CREATED` (via RecordCreated)
- `CREATED` → `UPDATED` (via RecordUpdated)
- `UPDATED` → `UPDATED` (via RecordUpdated, version increments)

---

### SyntheticEntry (Key Value Entity)

Simple key-value state for testing direct state persistence.

| Field          | Type    | Description                    |
|----------------|---------|--------------------------------|
| entryId        | String  | Unique identifier (entity ID)  |
| data           | String  | Arbitrary data payload         |
| lastUpdated    | Instant | Timestamp of last modification |

**State transitions**:
- `null` → set (via set command)
- set → updated (via set command with new data)
- set/updated → `null` (via delete command)

---

### HealthCheckEntry (Key Value Entity)

Dedicated entity for health check persistence round-trip. Fixed entity ID: `"heartbeat"`.

| Field          | Type    | Description                          |
|----------------|---------|--------------------------------------|
| timestamp      | Instant | When the heartbeat was written       |
| status         | String  | Always "OK" when written             |

---

### ConsumerCounter (Key Value Entity)

Tracks events consumed by the synthetic consumer.

| Field          | Type    | Description                              |
|----------------|---------|------------------------------------------|
| counterId      | String  | Unique identifier (entity ID)            |
| eventCount     | long    | Total number of events consumed          |
| lastEventType  | String  | Type name of the most recent event       |
| lastEventAt    | Instant | Timestamp of the most recent event       |

---

### SyntheticWorkflow (Workflow)

State tracking for the validate-then-persist workflow.

| Field          | Type    | Description                                  |
|----------------|---------|----------------------------------------------|
| workflowId     | String  | Unique identifier (workflow ID)              |
| input          | String  | Original input data                          |
| status         | String  | STARTED, VALIDATED, COMPLETED, COMPENSATED   |
| failureReason  | String  | Reason for failure (if compensated)          |
| delaySeconds   | int     | Configured delay for persist step (0 = none) |

**State transitions**:
- `null` → `STARTED` (via start command)
- `STARTED` → `VALIDATED` (after validate step passes)
- `VALIDATED` → `COMPLETED` (after persist step succeeds)
- `VALIDATED` → `COMPENSATED` (after persist step fails, compensation runs)

---

## Views

### SyntheticRecordView

Projects SyntheticRecord entity events into a queryable read model.

| Field          | Type    | Source Event     |
|----------------|---------|------------------|
| recordId       | String  | entity ID        |
| name           | String  | RecordCreated    |
| value          | String  | RecordCreated/Updated |
| status         | String  | derived          |

**Queries**:
- Get all records → returns `SyntheticRecordEntries(List<SyntheticRecordEntry>)`
- Get by name → filter by name field

---

## Relationships

```
SyntheticRecordEntity ──events──▶ SyntheticRecordView (projection)
SyntheticRecordEntity ──events──▶ SyntheticEventConsumer ──writes──▶ ConsumerCounterEntity
SyntheticWorkflow ──step 2──▶ SyntheticEntryEntity (persist step)
HealthEndpoint ──write/read──▶ HealthCheckEntity
BurstEndpoint ──parallel calls──▶ SyntheticRecordEntity / SyntheticEntryEntity
```
