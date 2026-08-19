# Tasks: Multi-Region Test Scenarios

**Input**: Design documents from `/specs/002-multi-region-test-service/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED. Constitution III (Test Coverage) and plan.md require tests for the new code.

**Organization**: Tasks are grouped by user story in priority order (P1 → P2 → P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story the task belongs to (US1–US7)

## Path Conventions

Single Akka SDK project. Source: `src/main/java/com/example/{domain,application,api}`.
Tests: `src/test/java/com/example/{domain,application,api}`. Docs/spec:
`specs/002-multi-region-test-service/`.

## Scope note

US1–US5 and the operational parts of US7 validate **existing** components; their
deliverable is executable validation procedures in `quickstart.md`, not new code.
The **new code** lives in US7 (region identity) and US6 (topic publishing).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration needed before topic and multi-region work.

- [X] T001 [P] Add `pulse.topic.publish-mode = origin-only` and document optional `akka.javasdk.dev-mode.eventing.support` (kafka / google-pubsub-emulator) in `src/main/resources/application.conf`
- [X] T002 [P] Confirm the deployed-broker configuration steps (`akka projects config set broker ...`) are documented in `specs/002-multi-region-test-service/quickstart.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Facts the replication and topic stories depend on.

- [X] T003 Confirm all `SyntheticRecordEvent` variants carry `@TypeName` (FR-001, FR-009) in `src/main/java/com/example/domain/SyntheticRecordEvent.java` (no change expected — verify)

**Checkpoint**: Foundation ready — user story work can begin.

---

## Phase 3: User Story 1 - Event Sourced Entity Replication (Priority: P1) 🎯 MVP

**Goal**: Validate that `SyntheticRecordEntity` state replicates across regions.

**Independent Test**: Create a record via Region A; read it from Region B; confirm convergence.

- [X] T004 [P] [US1] Author executable validation steps for AL-1, AL-2, AL-3 (create/update/rapid-write, read from the other region) in `specs/002-multi-region-test-service/quickstart.md`
- [X] T005 [P] [US1] Author AL-10 validation (invalid write to a non-primary region returns the same error the primary returns) in `specs/002-multi-region-test-service/quickstart.md`

**Checkpoint**: US1 replication scenarios are runnable against a two-region deploy.

---

## Phase 4: User Story 2 - Key Value Entity Replication (Priority: P1)

**Goal**: Validate that `SyntheticEntryEntity` state (including empty/deleted state) replicates.

**Independent Test**: Set an entry via Region A; read from Region B; delete via A; confirm empty state in B.

- [X] T006 [P] [US2] Author AL-4 validation (set/get/delete across regions, empty-state replication) in `specs/002-multi-region-test-service/quickstart.md`

**Checkpoint**: US2 KVE replication scenarios are runnable.

---

## Phase 5: User Story 7 - Operational Resilience & Region Identity (Priority: P1)

**Goal**: Report the current region from the health endpoint (FR-014) and document the
operational procedures (down/bring-up/switch-primary).

**Independent Test**: `GET /pulse/health` from each region returns that region's identity;
downing a region leaves the survivor serving traffic.

### Implementation for User Story 7

- [X] T007 [P] [US7] Add a `region` field to `HealthCheckEntry` in `src/main/java/com/example/domain/HealthCheckEntry.java`
- [X] T008 [US7] Update `HealthCheckEntity.set()`/`get()` to read and store `commandContext().selfRegion()` in `src/main/java/com/example/application/HealthCheckEntity.java` (depends on T007)
- [X] T009 [P] [US7] Unit test `HealthCheckEntity` region behavior (KeyValueEntityTestKit) in `src/test/java/com/example/application/HealthCheckEntityTest.java` (depends on T008)
- [X] T010 [US7] Add `region` to `HealthUpResponse`/`HealthDownResponse` and surface it in `health()` in `src/main/java/com/example/api/PulseEndpoint.java` (depends on T008)
- [X] T011 [US7] Update the health test to assert the `region` field in `src/test/java/com/example/api/PulseEndpointIntegrationTest.java` (depends on T010)
- [X] T012 [P] [US7] Author OP-1, OP-2, OP-3 procedures (down region, bring up region, switch primary) in `specs/002-multi-region-test-service/quickstart.md`
- [X] T013 [P] [US7] Author OP-10 (region identity via `/pulse/health`) and OP-8/SC-003 (replication-lag observation in Control Tower) in `specs/002-multi-region-test-service/quickstart.md`

**Checkpoint**: Health endpoint reports region; operational procedures documented.

---

## Phase 6: User Story 3 - Workflow Durability Across Regions (Priority: P2)

**Goal**: Validate that `SyntheticWorkflow` is durable and resumes in a failover region.

**Independent Test**: Start a workflow in Region A; query from Region B; trigger compensation; simulate failover.

- [X] T014 [P] [US3] Author AL-6, AL-7, OP-9 validation (start/query across regions, compensation, in-flight failover) in `specs/002-multi-region-test-service/quickstart.md`

**Checkpoint**: US3 workflow scenarios are runnable.

---

## Phase 7: User Story 4 - View Eventual Consistency Across Regions (Priority: P2)

**Goal**: Validate that `SyntheticRecordView` converges when queried from any region.

**Independent Test**: Create records in Region A; query the view from Region B; confirm convergence.

- [X] T015 [P] [US4] Author AL-5 validation (query view from non-primary region after creating on the primary) in `specs/002-multi-region-test-service/quickstart.md`

**Checkpoint**: US4 view scenarios are runnable.

---

## Phase 8: User Story 6 - Topic Publishing from the Active Region (Priority: P2)

**Goal**: Publish record events to a topic once per event via origin-based filtering, and
make duplicate publishing observable through a counter. This is the primary new code.

**Independent Test**: Create records; confirm `GET /pulse/topic-counter/synthetic-record-events`
shows one message per event in origin-only mode; switch to every-region mode and confirm the count scales with regions.

### Implementation for User Story 6

- [X] T016 [P] [US6] Create `SyntheticTopicMessage` record with `@TypeName` (recordId, eventType, value, originRegion) in `src/main/java/com/example/domain/SyntheticTopicMessage.java`
- [X] T017 [P] [US6] Create `TopicMessageCounter` record (counterId, messageCount, lastOriginRegion, lastMessageAt, `increment(...)`, `empty(...)`) in `src/main/java/com/example/domain/TopicMessageCounter.java`
- [X] T018 [US6] Create `TopicMessageCounterEntity` (KVE) with `increment`/`get` in `src/main/java/com/example/application/TopicMessageCounterEntity.java` (depends on T017)
- [X] T019 [P] [US6] Unit test `TopicMessageCounterEntity` (KeyValueEntityTestKit) in `src/test/java/com/example/application/TopicMessageCounterEntityTest.java` (depends on T018)
- [X] T020 [US6] Create `PulseTopicSettings` (publish-mode enum) and provide it from `Bootstrap` reading `pulse.topic.publish-mode` in `src/main/java/com/example/application/PulseTopicSettings.java` and `src/main/java/com/example/Bootstrap.java`
- [X] T021 [US6] Implement `SyntheticTopicProducer` (`@Consume.FromEventSourcedEntity(SyntheticRecordEntity)` + `@Produce.ToTopic("synthetic-record-events")`; origin-only guard `if (!messageContext().hasLocalOrigin()) return effects().ignore();`; add `ce-subject` + `ce-origin-region` metadata) in `src/main/java/com/example/application/SyntheticTopicProducer.java` (depends on T016, T020)
- [X] T022 [US6] Integration test `SyntheticTopicProducer` publishes once for a local-origin event using `withTopicOutgoingMessages("synthetic-record-events")` + `expectOneTyped(...)` in `src/test/java/com/example/application/SyntheticTopicProducerIntegrationTest.java` (depends on T021)
- [X] T023 [US6] Implement `SyntheticTopicConsumer` (`@Consume.FromTopic("synthetic-record-events")` → `TopicMessageCounterEntity.increment`) in `src/main/java/com/example/application/SyntheticTopicConsumer.java` (depends on T016, T018)
- [X] T024 [US6] Integration test `SyntheticTopicConsumer` increments the counter using `withTopicIncomingMessages(...)` + `publish(...)` + Awaitility in `src/test/java/com/example/application/SyntheticTopicConsumerIntegrationTest.java` (depends on T023)
- [X] T025 [US6] Add `GET /pulse/topic-counter/{counterId}` returning `TopicMessageCounter` in `src/main/java/com/example/api/PulseEndpoint.java` (depends on T018)
- [X] T026 [US6] Add an endpoint integration test for `/pulse/topic-counter/{id}` in `src/test/java/com/example/api/PulseEndpointIntegrationTest.java` (depends on T025)
- [X] T027 [P] [US6] Author AL-11, AL-12, AL-13, DI-4, DI-5 topic validation (origin-only vs every-region counts, subject/metadata, broker unavailable, origin-region failure limitation) in `specs/002-multi-region-test-service/quickstart.md`

**Checkpoint**: Topic producer/consumer work in a single region; counts are observable; cross-region behavior documented for a two-region deploy.

---

## Phase 9: User Story 5 - Consumer Processing Across Regions (Priority: P3)

**Goal**: Validate that `SyntheticEventConsumer` processes events idempotently across regions.

**Independent Test**: Trigger record changes; confirm `GET /pulse/consumers/synthetic-record-counter` reflects each event once.

- [X] T028 [P] [US5] Author AL-8 and DI-1 validation (consumer counter per event; consumer restart resumes from last position) in `specs/002-multi-region-test-service/quickstart.md`

**Checkpoint**: US5 consumer scenarios are runnable.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [X] T029 [P] Add a `service.replication.replicatedRead.primarySelectionMode` descriptor snippet (pinned-region / request-region) and reference it from `specs/002-multi-region-test-service/quickstart.md`
- [X] T030 [P] Update `README.md` with multi-region + topic-counter curl examples and a link to the quickstart
- [X] T031 [P] Author AL-9 validation (FR-006): observe `SyntheticTimedAction` cross-region execution — timer fires in the expected region with no duplicate execution — in `specs/002-multi-region-test-service/quickstart.md` (observation only; no documented multi-region guarantee — see plan.md risks)
- [X] T032 [P] Author SC-007 throughput validation: drive ≥100 concurrent synthetic operations/sec across both regions using the existing BurstEndpoint (`POST /pulse/burst/`) and record results in `specs/002-multi-region-test-service/quickstart.md`
- [X] T033 Run `mvn verify` and confirm all new unit/integration tests pass
- [ ] T034 Walk through `specs/002-multi-region-test-service/quickstart.md` end-to-end on a two-region deploy and record results

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: after Setup.
- **User Stories (Phases 3–9)**: after Foundational. Operational stories (US1, US2, US3, US4, US5) are independent and can proceed in any order/parallel. Code stories: US7 (health region) is independent; US6 (topics) depends only on Setup T001.
- **Polish (Phase 10)**: after the desired stories are complete.

### User Story Dependencies

- **US1, US2 (P1)**: docs only — independent.
- **US7 (P1)**: code — independent of other stories.
- **US3, US4 (P2)**: docs only — independent.
- **US6 (P2)**: code — needs Setup T001 (publish-mode config). Independent of other stories.
- **US5 (P3)**: docs only — independent.

### Within User Story 6 (code order)

- Domain records (T016, T017) → entity (T018) → its test (T019)
- Settings/DI (T020) → producer (T021) → producer test (T022)
- Consumer (T023) → consumer test (T024)
- Endpoint (T025) → endpoint test (T026)
- Scenario docs (T027) any time after the endpoint exists

> Note: Akka TestKit tests reference the component classes, so each test follows its
> component rather than being written to fail first.

### Parallel Opportunities

- Setup T001, T002 in parallel.
- US1–US5 doc tasks (T004, T005, T006, T014, T015, T028) in parallel — different sections, same file; coordinate edits.
- Within US6: T016 and T017 in parallel; T019 parallel once T018 exists.
- Within US7: T007 then T009 parallel with doc tasks T012, T013.

---

## Parallel Example: User Story 6 (domain records)

```bash
Task: "Create SyntheticTopicMessage record in src/main/java/com/example/domain/SyntheticTopicMessage.java"
Task: "Create TopicMessageCounter record in src/main/java/com/example/domain/TopicMessageCounter.java"
```

---

## Implementation Strategy

### MVP (code first)

The operational P1 stories (US1, US2) need no code. The smallest **code** increment is:

1. Phase 1 Setup → Phase 2 Foundational
2. US7 (health region identity) — verify region reporting
3. US6 (topic publishing) — the core new capability
4. Fill in the operational validation docs (US1–US5, US7 ops) against a two-region deploy

### Incremental Delivery

1. Setup + Foundational.
2. US7 region identity → test → demo `/pulse/health` region field.
3. US6 topic publishing → test → demo `/pulse/topic-counter`.
4. Author operational validation procedures (US1–US5) → validate on a two-region deploy.

---

## Notes

- `[P]` tasks touch different files (or clearly separate doc sections) with no incomplete dependencies.
- Doc tasks target `quickstart.md`; coordinate concurrent edits to the same file.
- Cross-region behavior (origin filtering, failover, replication lag) is validated
  operationally per `quickstart.md`; automated tests cover single-region behavior only.
- Known limitations (Timed Action region guarantee, origin-region-failure in DI-5) are
  documented, not code-enforced — see `plan.md` risks and `contracts/topic-messaging.md`.
- Commit after each task or logical group.
