# Feature Specification: Multi-Region Test Scenarios

**Feature Branch**: `002-multi-region-test-service`
**Created**: 2026-05-07
**Updated**: 2026-08-05
**Status**: Draft
**Input**: User description: "Document multi-region test scenarios for the synthetic test suite. Reuse the existing synthetic components. Do not introduce a real business domain."

## Overview

This feature extends the existing Akka Pulse synthetic test suite. It adds documented
multi-region test scenarios. The goal is to validate replication, failover, and recovery
behavior across regions. The synthetic components stay simple. They do not model a real
business domain. Their only purpose is infrastructure validation.

This spec reuses the components that already exist in the service. It also adds two new
components (marked below) to cover cross-region topic publishing:

| Component | Type | New? | Multi-region focus |
|-----------|------|------|--------------------|
| SyntheticRecordEntity | Event Sourced Entity | existing | Event journal replication across regions |
| SyntheticEntryEntity | Key Value Entity | existing | Key-value state replication across regions |
| HealthCheckEntity | Key Value Entity | existing | Region identity and replication probe |
| ConsumerCounterEntity | Key Value Entity | existing | Consumer progress per region |
| SyntheticRecordView | View | existing | Read-side projection convergence across regions |
| SyntheticWorkflow | Workflow | existing | Durable execution and failover across regions |
| SyntheticEventConsumer | Consumer | existing | At-least-once processing without duplicates |
| SyntheticTimedAction | Timed Action | existing | Timer fires in the expected region only |
| SyntheticTopicProducer | Consumer + Producer | **new** | Publishes record events to a topic once per event, from the origin region only |
| SyntheticTopicConsumer | Consumer | **new** | Reads the topic and counts messages so duplicate publishing is observable |

### The multi-region topic-publishing concern

Consumers are not replicated the way entities are. An entity replicates its events to every
region. A Consumer then runs in each region and processes the same replicated events. This
means a Consumer that publishes to a topic would publish the same message once per region.
That produces duplicate messages downstream.

For multi-region deployments, a producer must decide where to publish:

- **Publish from the origin region only** (default for this spec). The producer publishes an
  event only when the event first occurred in the local region. Every other region skips it.
  The topic receives each event exactly once, regardless of region count.
- **Publish from every region.** Valid only when the topic is regional and downstream
  consumers expect per-region messages. This spec treats this as an alternative mode to test.

The event origin is available to the producer. It can compare the event's origin region with
the region where the producer runs and publish conditionally. This is the core behavior the
topic scenarios validate.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Event Sourced Entity Replication (Priority: P1)

As a platform engineer, I want to write a synthetic record in one region and read it in
another region, so that I can validate event journal replication and eventual consistency.

**Why this priority**: Event Sourced Entities are the primary mechanism for multi-region
state replication. The synthetic record entity already exists. Validating its replication
is foundational to all other scenarios.

**Independent Test**: Create a synthetic record via Region A. Read it from Region B. Verify
the state converges. This delivers a working multi-region entity with an auditable event
history.

**Acceptance Scenarios**:

1. **Given** a synthetic record exists in Region A, **When** a user queries the record from Region B, **Then** the record returns with the correct value (eventually consistent).
2. **Given** a record with a value, **When** the value is updated via Region A, **Then** the updated value and version replicate to Region B within acceptable latency.
3. **Given** no record exists with a given ID, **When** a record is created via Region A, **Then** the record becomes queryable from Region B after replication.
4. **Given** a write is sent to a non-primary region, **When** the write is forwarded to the primary, **Then** the write succeeds and the result replicates back.
5. **Given** a record exists, **When** rapid successive updates occur in one region, **Then** all regions eventually see the final value with correct event ordering.

---

### User Story 2 - Key Value Entity Replication (Priority: P1)

As a platform engineer, I want to set a synthetic entry in one region and read it in another
region, so that I can validate key-value state replication.

**Why this priority**: Key Value Entities are a fundamental component type. The synthetic
entry entity already exists. Validating its replication confirms simple state storage works
across regions.

**Independent Test**: Set an entry via Region A. Read it from Region B. Verify the value
converges. Delete it and verify the empty state replicates.

**Acceptance Scenarios**:

1. **Given** an entry is set in Region A, **When** the entry is read from Region B, **Then** the value returns (eventually consistent).
2. **Given** an entry exists, **When** the value is updated in Region A, **Then** Region B eventually sees the new value.
3. **Given** an entry exists, **When** it is deleted in Region A, **Then** Region B eventually sees the empty state.

---

### User Story 3 - Workflow Durability Across Regions (Priority: P2)

As a platform engineer, I want to start a synthetic workflow that survives a region failure,
so that I can validate durable execution and failover.

**Why this priority**: Workflows validate the durable execution engine. Their multi-region
behavior (primary routing, failover, compensation) is essential to validate. The synthetic
workflow already runs a validate-then-persist pattern with compensation.

**Independent Test**: Start a workflow. Verify step progression. Simulate a region failure.
Confirm the workflow resumes in the failover region.

**Acceptance Scenarios**:

1. **Given** a valid start command, **When** the synthetic workflow runs, **Then** it progresses through its validate and persist steps to completed status.
2. **Given** a workflow is in progress, **When** the primary region becomes unavailable, **Then** the workflow resumes in the failover region and completes with no duplicate step execution.
3. **Given** a workflow started with a trigger-failure command, **When** the persist step fails, **Then** the compensation strategy runs and the workflow reaches compensated status.
4. **Given** a workflow was started in Region A, **When** its status is queried from Region B, **Then** the correct current step and state are returned.

---

### User Story 4 - View Eventual Consistency Across Regions (Priority: P2)

As a platform engineer, I want to query the synthetic view from any region, so that I can
validate read-side projection convergence.

**Why this priority**: Views are the primary query mechanism. Their eventual consistency
across regions is important to validate alongside the entities they consume from. The
synthetic view already projects synthetic record events.

**Independent Test**: Create synthetic records in Region A. Query the view from Region B.
Verify results converge.

**Acceptance Scenarios**:

1. **Given** a record is created in Region A, **When** the view is queried from Region B, **Then** the record eventually appears in query results.
2. **Given** a record value changes, **When** the view is queried, **Then** the view eventually reflects the updated value.
3. **Given** multiple records exist, **When** the view is queried by name from any region, **Then** all matching records are returned.
4. **Given** the view is queried from a non-primary region, **When** replication is active, **Then** the projection reflects the replicated events.

---

### User Story 5 - Consumer Processing Across Regions (Priority: P3)

As a platform engineer, I want the synthetic consumer to process record events without
duplicates, so that I can validate at-least-once delivery and idempotent processing across
regions.

**Why this priority**: Consumers validate the event-driven messaging infrastructure. This is
a secondary concern after core entity and view behavior is validated. The synthetic consumer
already tracks processed events in a counter entity.

**Independent Test**: Trigger record changes. Verify the consumer counter reflects the
processed events. Confirm no duplicate counting across regions.

**Acceptance Scenarios**:

1. **Given** a record event occurs, **When** the consumer processes it, **Then** the consumer counter increments and records the last event type and timestamp.
2. **Given** events originate from different regions, **When** consumers process them, **Then** the counter reflects each event once (idempotent processing).
3. **Given** a consumer restarts after a crash, **When** it resumes, **Then** it continues from the last committed position with no lost or duplicated events.

---

### User Story 6 - Topic Publishing from the Active Region (Priority: P2)

As a platform engineer, I want a synthetic producer that publishes record events to a topic
exactly once, so that I can validate cross-region publishing does not create duplicates.

**Why this priority**: Topic publishing is a common integration pattern and a key multi-region
concern. A Consumer runs in every region, so a naive producer publishes duplicate messages.
Validating origin-based conditional publishing confirms the deployment behaves correctly.

**Independent Test**: Create records in Region A. Confirm the topic receives each event once,
even though a producer runs in both regions. Switch the producer to publish-from-every-region
mode and confirm the expected message count changes.

**Acceptance Scenarios**:

1. **Given** a record event first occurs in Region A, **When** the producer runs in both regions in origin-only mode, **Then** the topic receives exactly one message for that event.
2. **Given** the producer runs in publish-from-every-region mode, **When** a record event is replicated to both regions, **Then** the topic receives one message per region.
3. **Given** messages are published to the topic, **When** the downstream consumer reads them, **Then** each message carries the originating record ID as its subject and the origin region as metadata.
4. **Given** the primary migrates to the surviving region (request-region mode), **When** a new record event is written there, **Then** the surviving region is the origin and publishes it (no loss for new events). **Note**: in origin-only mode, an event written in the origin region but not yet published before that region failed is not republished elsewhere — this is a documented limitation (see DI-5). Every-region mode avoids this loss.

---

### User Story 7 - Operational Resilience Validation (Priority: P1)

As an operations engineer, I want to run region failure, recovery, and primary-switch
procedures, so that I can validate service behavior and data integrity at each step.

**Why this priority**: Operational resilience is the primary reason for multi-region
deployment. Validating failover and recovery is as critical as the application logic itself.

**Independent Test**: Perform operational actions (down a region, bring it up, switch the
primary). Verify service behavior and data consistency at each step.

**Acceptance Scenarios**:

1. **Given** a service running in two regions, **When** one region is downed, **Then** the surviving region continues to serve reads and writes.
2. **Given** a region was downed and writes occurred in the surviving region, **When** the downed region is brought back up, **Then** it catches up with all missed events and state.
3. **Given** the primary region is switched, **When** new writes are sent, **Then** the new primary processes them with no data loss.
4. **Given** the health endpoint is queried, **When** the service reports status, **Then** the response includes the current region identity and replication status.
5. **Given** the service is running, **When** replication lag is observed via monitoring, **Then** the lag is within acceptable bounds under normal conditions.

---

### Edge Cases

- What happens when a write is sent to a region that is being downed mid-request?
- How does the system behave when replication lag exceeds acceptable thresholds?
- What happens when a workflow step times out during a region switch?
- How does the synthetic record entity behave when the same record is modified in two regions under request-region primary selection mode?
- What happens when the consumer processes the same event twice (idempotency validation)?
- What happens when all regions are temporarily unavailable and then recover?
- What happens when the timed action is due while a region is down?
- What happens when the producer runs in every region but the topic is global (duplicate publishing)?
- What happens when the topic broker is temporarily unavailable during publishing?
- What happens when the origin region goes down before an event is published to the topic?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The existing Event Sourced Entity (SyntheticRecordEntity) MUST replicate its event journal and state to all regions. Its events (RecordCreated, RecordUpdated) MUST carry `@TypeName` annotations.
- **FR-002**: The existing Key Value Entity (SyntheticEntryEntity) MUST replicate its state to all regions, including the deleted/empty state.
- **FR-003**: The existing Workflow (SyntheticWorkflow) MUST support durable execution across regions. It MUST resume in a failover region with no duplicate step execution, and it MUST keep its validate-then-persist compensation behavior.
- **FR-004**: The existing View (SyntheticRecordView) MUST be queryable from any region and MUST converge on the replicated events.
- **FR-005**: The existing Consumer (SyntheticEventConsumer) MUST process record events at-least-once and MUST update the consumer counter idempotently so no event is counted twice.
- **FR-006**: The existing Timed Action (SyntheticTimedAction) MUST fire in the expected region only, with no duplicate execution across regions.
- **FR-007**: The service MUST include a producer (SyntheticTopicProducer) that consumes record events and publishes them to a topic. It MUST attach the record ID as the message subject and the origin region as metadata.
- **FR-008**: The producer MUST support an origin-only publishing mode that publishes an event only from its origin region, so the topic receives each event exactly once regardless of region count.
- **FR-009**: The producer MUST support an alternative publish-from-every-region mode for testing regional-topic behavior. The active mode MUST be selectable via build-time configuration (not switchable at runtime).
- **FR-010**: The service MUST include a downstream consumer (SyntheticTopicConsumer) that reads the topic and records a message count, so duplicate publishing is observable and verifiable.
- **FR-011**: The service MUST be deployable to at least two Akka regions with replicated state across regions.
- **FR-012**: The service MUST support both `pinned-region` and `request-region` primary selection modes for testing different routing strategies.
- **FR-013**: The service MUST handle validation errors gracefully even when a write is forwarded to the primary region, returning meaningful error responses.
- **FR-014**: The health/status endpoint MUST report the current region identity and replication status for operational testing.
- **FR-015**: The existing HTTP endpoints MUST expose every operation needed to exercise the multi-region scenarios (record create/update/query, entry set/get/delete, workflow start/query, view query, consumer counter query, topic counter query, timed action schedule).
- **FR-016**: All endpoints MUST keep their existing ACL annotations so external monitoring tools can reach them.

### Key Entities

- **SyntheticRecord (State)**: State of the Event Sourced Entity. Key attributes: record ID, name, value, version, timestamp. Contains validation and mutation logic.
- **SyntheticRecordEvent (Sealed Interface)**: Events for the record entity: RecordCreated, RecordUpdated. Each annotated with `@TypeName`.
- **SyntheticEntry (State)**: State of the Key Value Entity. Key attributes: entry ID, data payload, last updated timestamp.
- **SyntheticWorkflowState (Workflow State)**: State of the synthetic workflow. Key attributes: workflowId, input, status (STARTED/COMPENSATED/completed), failureReason, delaySeconds.
- **SyntheticViewEntry (View Row)**: Read-side projection of synthetic record data. Key attributes: record ID, name, value.
- **ConsumerCounter (State)**: Tracks events processed by the consumer. Key attributes: counter ID, event count, last event type, last event timestamp.
- **SyntheticTopicMessage (Message)**: Message published to the topic. Key attributes: record ID (message subject), event type, value, origin region.
- **TopicMessageCounter (State)**: Tracks messages read from the topic by the downstream consumer. Key attributes: counter ID, message count, last origin region, last message timestamp. Used to detect duplicate publishing.

## Multi-Region Test Scenarios

### Application Logic Scenarios

| ID    | Scenario                                          | What to Validate                                                             |
|-------|---------------------------------------------------|------------------------------------------------------------------------------|
| AL-1  | Create record in Region A, read in Region B       | Replication of initial state, eventual consistency timing                    |
| AL-2  | Update record in Region A, read in Region B       | Replication of state changes, event ordering                                 |
| AL-3  | Rapid successive record writes in one region      | Event sequence numbers, replication ordering guarantees                      |
| AL-4  | Set/delete key-value entry across regions         | Key-value replication, empty-state replication                               |
| AL-5  | Query synthetic view from non-primary region      | View eventual consistency, correct projection of replicated events           |
| AL-6  | Start workflow in Region A, query in Region B      | Workflow state replication, step visibility across regions                   |
| AL-7  | Workflow compensation across regions              | Compensation step runs correctly when triggered from a non-primary region    |
| AL-8  | Consumer event processing in each region          | At-least-once delivery, idempotent counter update                            |
| AL-9  | Timed action execution across regions             | Timer fires in the expected region, no duplicate execution                   |
| AL-10 | Entity validation in non-primary region           | Error responses are correct even when the write is forwarded to the primary  |
| AL-11 | Topic publish in origin-only mode                  | Topic receives each event once, even with a producer in every region         |
| AL-12 | Topic publish in publish-from-every-region mode    | Topic receives one message per region, message metadata shows origin region  |
| AL-13 | Topic message subject and metadata                 | Message carries record ID as subject and origin region as metadata           |

### Operational Scenarios

| ID    | Scenario                                          | What to Validate                                                             |
|-------|---------------------------------------------------|------------------------------------------------------------------------------|
| OP-1  | Down a region                                      | Surviving region handles all traffic, no data loss                           |
| OP-2  | Bring up a downed region                           | Replication catches up, view rebuilds, consumers resume                      |
| OP-3  | Switch primary region (pinned mode)                | Writes route to new primary, no data loss during switch                      |
| OP-4  | Primary migration (request-region mode)            | Primary follows writes, seamless transition                                  |
| OP-5  | Network partition and reconnection                 | Conflict detection, resolution by primary                                    |
| OP-6  | Rolling deployment across regions                  | Service stays available during deployment, replication uninterrupted         |
| OP-7  | Region addition to running service                 | New region catches up with existing state, views and consumers start         |
| OP-8  | Monitor replication lag                             | Lag is observable and within expected bounds                                 |
| OP-9  | Workflow in-flight during region failure           | Workflow resumes in failover region, no duplicate step execution             |
| OP-10 | Region identity via health endpoint                | Health endpoint reports current region and replication status                |

### Dependency & Integration Scenarios

| ID    | Scenario                                          | What to Validate                                                             |
|-------|---------------------------------------------------|------------------------------------------------------------------------------|
| DI-1  | Consumer restart after crash                       | Offset tracking resumes from last committed position                         |
| DI-2  | View rebuild after data loss                        | View can be rebuilt from replicated entity events                            |
| DI-3  | Health probe during startup                          | Health endpoint reports non-ready status until the region is ready           |
| DI-4  | Topic broker unavailable during publishing         | Events retried, no permanent loss once the broker recovers                   |
| DI-5  | Origin region fails before publishing               | Origin-only mode may lose an in-flight event (documented limitation); every-region mode avoids it |

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All synthetic record and entry state changes replicate to the secondary region within 5 seconds under normal network conditions.
- **SC-002**: Users can read synthetic data from any region with response times under 500ms.
- **SC-003**: When a region is downed, the surviving region serves 100% of traffic with no user-visible errors within 30 seconds.
- **SC-004**: When a downed region is brought back up, it fully catches up with replicated state within 2 minutes for a dataset of 1000 synthetic records.
- **SC-005**: Synthetic workflows complete successfully (or compensate cleanly) 100% of the time, including during region failover scenarios.
- **SC-006**: The consumer counter reflects each record event exactly once under normal operation, with no duplicate counting across regions.
- **SC-007**: The service handles at least 100 concurrent synthetic operations per second across both regions without degradation.
- **SC-008**: All operational procedures (down region, bring up region, switch primary) complete without data loss or corruption.
- **SC-009**: In origin-only publishing mode, the topic receives exactly one message per record event across both regions, with zero duplicates under normal operation.

## Clarifications

### Session 2026-05-07

- Q: Should we include service-to-service streaming scenarios? → A: No, service-to-service streaming is out of scope.
- Q: Should we test replicated-writes (CRDT) mode in addition to replicated-reads? → A: No, focus only on replicated-reads (single primary writer) mode.

### Session 2026-08-05

- Q: Should the spec introduce a real business domain (inventory, orders, catalog) or reuse the existing synthetic components? → A: Reuse the existing synthetic components. The service is a synthetic test suite, not a real application. Multi-region behavior is validated against SyntheticRecordEntity, SyntheticEntryEntity, SyntheticWorkflow, SyntheticRecordView, SyntheticEventConsumer, and SyntheticTimedAction.
- Q: Should the consumer publish to an external topic for the multi-region scenarios? → A: Yes. Topic publishing is an important multi-region concern. A Consumer runs in every region, so a producer must decide where to publish to avoid duplicates. The spec adds a new SyntheticTopicProducer (origin-only mode by default, with an alternative publish-from-every-region mode) and a new SyntheticTopicConsumer that counts topic messages so duplicate publishing is observable. The existing SyntheticEventConsumer keeps its counter role.
- Q: How should the producer avoid duplicate topic messages across regions? → A: Publish only from the event's origin region by default. Compare the event origin region with the producer's local region and publish conditionally. Keep a configurable alternative mode that publishes from every region for regional-topic testing.

## Assumptions

- The multi-region scenarios reuse the synthetic components that already exist in the service. No new business domain is introduced.
- The service is deployed to exactly two Akka regions for initial testing, with the option to add more later.
- The synthetic components stay intentionally simple. Their purpose is infrastructure validation, not business logic.
- The primary selection mode defaults to `pinned-region`. The service works with both modes.
- Standard Akka SDK replication defaults are used unless a specific test scenario needs tuning.
- Monitoring and observability use Akka's built-in Control Tower dashboards.
- The dataset is modest (hundreds to low thousands of synthetic records). This is a test service, not a production-scale benchmark.
- All endpoints keep their existing `@Acl` annotations for external testing access.
- Only replicated-reads replication mode is in scope. Replicated-writes (CRDT) mode is excluded.
- Service-to-service streaming is out of scope for this test service.
- The topic producer publishes from the origin region only by default. The alternative publish-from-every-region mode exists for testing regional-topic behavior.
- The publishing mode is set via build-time configuration. Changing the mode requires a redeploy. Runtime switching is out of scope.
- The topic broker configuration (regional vs global) is a deployment concern. The spec validates producer behavior against both, but does not mandate a specific broker.
