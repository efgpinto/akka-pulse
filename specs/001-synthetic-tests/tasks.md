# Tasks: Synthetic Test Suite & Health Monitoring

**Input**: Design documents from `/specs/001-synthetic-tests/`
**Prerequisites**: plan.md, spec.md, data-model.md, contracts/http-api.md, research.md

**Tests**: Included per FR-012 (unit tests for entities) and FR-013 (integration tests for views, endpoints, workflows).

**Organization**: Tasks grouped by user story. US9 (configurable delay) is cross-cutting and integrated into US2, US3, US5.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup

**Purpose**: Project initialization, dependencies, package structure

- [x] T001 Update pom.xml to add akka-openapi-maven-plugin (`sh.oso:akka-openapi-maven-plugin:1.0.0`) with output to `target/classes/static-resources/openapi.yaml` in pom.xml
- [x] T002 Update pom.xml to set `<name>akka-pulse</name>` and verify groupId/artifactId in pom.xml
- [x] T003 [P] Create package directories: `src/main/java/com/example/domain/`, `src/main/java/com/example/application/`, `src/main/java/com/example/api/`
- [x] T004 [P] Create test package directories: `src/test/java/com/example/domain/`, `src/test/java/com/example/application/`, `src/test/java/com/example/api/`

---

## Phase 2: Foundational (Domain Layer)

**Purpose**: All domain records and events shared across multiple user stories. MUST complete before any user story.

**CRITICAL**: No user story work can begin until this phase is complete.

- [x] T005 [P] Create SyntheticRecord state record in src/main/java/com/example/domain/SyntheticRecord.java — fields: recordId, name, value, status, version, lastUpdated. Include `withValue()` mutation method and validation logic.
- [x] T006 [P] Create SyntheticRecordEvent sealed interface in src/main/java/com/example/domain/SyntheticRecordEvent.java — events: RecordCreated (with @TypeName "record-created"), RecordUpdated (with @TypeName "record-updated")
- [x] T007 [P] Create SyntheticEntry state record in src/main/java/com/example/domain/SyntheticEntry.java — fields: entryId, data, lastUpdated
- [x] T008 [P] Create HealthCheckEntry state record in src/main/java/com/example/domain/HealthCheckEntry.java — fields: timestamp, status
- [x] T009 [P] Create ConsumerCounter state record in src/main/java/com/example/domain/ConsumerCounter.java — fields: counterId, eventCount, lastEventType, lastEventAt. Include `increment()` method.
- [x] T010 [P] Create SyntheticWorkflowState record in src/main/java/com/example/domain/SyntheticWorkflowState.java — fields: workflowId, input, status, failureReason, delaySeconds. Include `with*` mutation methods for state transitions (STARTED, VALIDATED, COMPLETED, COMPENSATED).
- [x] T011 Verify domain layer compiles with `mvn compile`

**Checkpoint**: Domain layer complete — all state records and events ready for components

---

## Phase 3: User Story 1 - Health Check Probe (Priority: P1) — MVP

**Goal**: Deep health check endpoint that writes/reads a heartbeat to a KV Entity to verify persistence layer

**Independent Test**: `curl http://localhost:9000/pulse/health` returns 200 with `status: UP` and `persistenceCheck.status: OK`

### Implementation for User Story 1

- [x] T012 [US1] Create HealthCheckEntity (Key Value Entity) in src/main/java/com/example/application/HealthCheckEntity.java — fixed entity ID "heartbeat", set command writes timestamp+status, get command reads current state
- [x] T013 [US1] Create PulseEndpoint with health check in src/main/java/com/example/api/PulseEndpoint.java — `GET /pulse/health` writes heartbeat to HealthCheckEntity via ComponentClient, reads it back, returns HealthResponse with serviceName, version, timestamp, persistenceCheck result. Return 200 on success, 503 on failure. Add `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`
- [x] T014 [US1] Verify health endpoint compiles with `mvn compile`

### Tests for User Story 1

- [x] T015 [US1] Create PulseEndpointIntegrationTest in src/test/java/com/example/api/PulseEndpointIntegrationTest.java — test health endpoint returns 200 with expected fields using httpClient
- [x] T016 [US1] Verify tests pass with `mvn verify`

**Checkpoint**: Health check MVP deployed and testable

---

## Phase 4: User Story 2 - Event Sourced Entity + Configurable Delay (Priority: P1)

**Goal**: Synthetic ESE with create/update/query commands, supporting optional delay parameter (US9)

**Independent Test**: Create a record, update it, query it — verify state changes. Send a command with `delaySeconds: 3` and verify response takes ~3 seconds.

### Implementation for User Story 2

- [x] T017 [US2] Create SyntheticRecordEntity (Event Sourced Entity) in src/main/java/com/example/application/SyntheticRecordEntity.java — command handlers: create (accepts name, value, delaySeconds), update (accepts value, delaySeconds), get (returns current state). Apply `Thread.sleep(delaySeconds * 1000)` when delaySeconds > 0. Validate delaySeconds <= 300. Use `effects().persist(event).thenReply(...)`.
- [x] T018 [US2] Add ESE endpoints to PulseEndpoint in src/main/java/com/example/api/PulseEndpoint.java — `POST /pulse/records/{recordId}/create` (201 Created), `POST /pulse/records/{recordId}/update` (200 OK), `GET /pulse/records/{recordId}` (200 OK). Define request records CreateRecordRequest(name, value, delaySeconds) and UpdateRecordRequest(value, delaySeconds) as inner records.
- [x] T019 [US2] Verify compiles with `mvn compile`

### Tests for User Story 2

- [x] T020 [US2] Create SyntheticRecordEntityTest (unit test) in src/test/java/com/example/application/SyntheticRecordEntityTest.java — using EventSourcedTestKit: test create persists RecordCreated and state has correct fields, test update persists RecordUpdated and increments version, test get on non-existent returns empty, test create with invalid delay (>300) returns error
- [x] T021 [US2] Add ESE endpoint tests to PulseEndpointIntegrationTest in src/test/java/com/example/api/PulseEndpointIntegrationTest.java — test create/update/query flow via httpClient
- [x] T022 [US2] Verify all tests pass with `mvn verify`

**Checkpoint**: ESE with delay support fully functional

---

## Phase 5: User Story 3 - Key Value Entity + Configurable Delay (Priority: P1)

**Goal**: Synthetic KVE with set/get/delete commands, supporting optional delay parameter (US9)

**Independent Test**: Set a value, get it, update it, delete it — verify state changes at each step.

### Implementation for User Story 3

- [x] T023 [US3] Create SyntheticEntryEntity (Key Value Entity) in src/main/java/com/example/application/SyntheticEntryEntity.java — command handlers: set (accepts data, delaySeconds), get (returns state), delete (clears state). Apply `Thread.sleep` when delaySeconds > 0. Validate delaySeconds <= 300.
- [x] T024 [US3] Add KVE endpoints to PulseEndpoint in src/main/java/com/example/api/PulseEndpoint.java — `POST /pulse/entries/{entryId}` (200 OK), `GET /pulse/entries/{entryId}` (200 OK), `DELETE /pulse/entries/{entryId}` (200 OK). Define SetEntryRequest(data, delaySeconds) as inner record.
- [x] T025 [US3] Verify compiles with `mvn compile`

### Tests for User Story 3

- [x] T026 [US3] Create SyntheticEntryEntityTest (unit test) in src/test/java/com/example/application/SyntheticEntryEntityTest.java — using KeyValueEntityTestKit: test set stores value, test update overwrites, test delete clears state, test set with invalid delay returns error
- [x] T027 [US3] Add KVE endpoint tests to PulseEndpointIntegrationTest in src/test/java/com/example/api/PulseEndpointIntegrationTest.java — test set/get/delete flow via httpClient
- [x] T028 [US3] Verify all tests pass with `mvn verify`

**Checkpoint**: KVE with delay support fully functional

---

## Phase 6: User Story 4 - View (Priority: P2)

**Goal**: View projecting SyntheticRecord events into a queryable read model

**Independent Test**: Create records via ESE, query view by name and all — verify projected data appears.

**Depends on**: US2 (SyntheticRecordEntity must exist for event source)

### Implementation for User Story 4

- [x] T029 [US4] Create SyntheticRecordView in src/main/java/com/example/application/SyntheticRecordView.java — TableUpdater with `@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)`, handles RecordCreated and RecordUpdated events via `onEvent()`. Query methods: `getByName(String name)` returning `SyntheticRecordEntries(List<SyntheticRecordEntry>)` with `SELECT * AS entries FROM synthetic_records WHERE name = :name`, and `getAll()` returning same wrapper with `SELECT * AS entries FROM synthetic_records`. Define SyntheticRecordEntry and SyntheticRecordEntries as inner records.
- [x] T030 [US4] Add view endpoints to PulseEndpoint in src/main/java/com/example/api/PulseEndpoint.java — `GET /pulse/records/by-name/{name}`, `GET /pulse/records/all`
- [x] T031 [US4] Verify compiles with `mvn compile`

### Tests for User Story 4

- [x] T032 [US4] Create SyntheticRecordViewIntegrationTest in src/test/java/com/example/application/SyntheticRecordViewIntegrationTest.java — extends TestKitSupport, uses `withEventSourcedEntityIncomingMessages(SyntheticRecordEntity.class)`, publishes RecordCreated and RecordUpdated events, queries view via componentClient with Awaitility.await()
- [x] T033 [US4] Verify all tests pass with `mvn verify`

**Checkpoint**: View projection and queries working

---

## Phase 7: User Story 5 - Workflow + Configurable Delay (Priority: P2)

**Goal**: Validate-then-persist workflow with normal, trigger-failure, and delayed modes

**Independent Test**: Start workflow in normal mode — verify COMPLETED. Start in trigger-failure mode — verify COMPENSATED. Start with delaySeconds — verify step takes that long.

**Depends on**: US3 (SyntheticEntryEntity for persist step)

### Implementation for User Story 5

- [x] T034 [US5] Create SyntheticWorkflow in src/main/java/com/example/application/SyntheticWorkflow.java — extends Workflow<SyntheticWorkflowState>. Command handler: `start(StartWorkflowRequest)` with fields input, mode, delaySeconds. Steps: validateStep (checks input, passes through for trigger-failure), persistStep (writes to SyntheticEntryEntity via ComponentClient; for trigger-failure mode, throws to trigger compensation; applies Thread.sleep for delaySeconds), compensateStep (marks state COMPENSATED, ends). WorkflowSettings: step timeouts, `RecoverStrategy.maxRetries(1).failoverTo(SyntheticWorkflow::compensateStep)` on persistStep. Query handler: `getStatus()` returns current state. Define StartWorkflowRequest as inner record.
- [x] T035 [US5] Create WorkflowEndpoint in src/main/java/com/example/api/WorkflowEndpoint.java — `@HttpEndpoint("/pulse/workflows")`, `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`. `POST /{workflowId}/start` (201 Created), `GET /{workflowId}` (200 OK). Uses ComponentClient to call workflow.
- [x] T036 [US5] Verify compiles with `mvn compile`

### Tests for User Story 5

- [x] T037 [US5] Create WorkflowEndpointIntegrationTest in src/test/java/com/example/api/WorkflowEndpointIntegrationTest.java — extends TestKitSupport: test normal mode reaches COMPLETED, test trigger-failure mode reaches COMPENSATED, test status query returns current state. Use httpClient and Awaitility for async workflow completion.
- [x] T038 [US5] Verify all tests pass with `mvn verify`

**Checkpoint**: Workflow with compensation and delay fully functional

---

## Phase 8: User Story 6 - Consumer (Priority: P2)

**Goal**: Consumer reacting to SyntheticRecordEntity events, incrementing a counter in ConsumerCounterEntity

**Independent Test**: Create/update records via ESE endpoint, query consumer counter — verify eventCount increases.

**Depends on**: US2 (SyntheticRecordEntity as event source)

### Implementation for User Story 6

- [x] T039 [US6] Create ConsumerCounterEntity (Key Value Entity) in src/main/java/com/example/application/ConsumerCounterEntity.java — command handler: increment(IncrementCommand) with eventType field, updates counter state using `ConsumerCounter.increment()`. Query handler: get() returns current state.
- [x] T040 [US6] Create SyntheticEventConsumer in src/main/java/com/example/application/SyntheticEventConsumer.java — `@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)`, handles all SyntheticRecordEvent types. On each event, calls ConsumerCounterEntity.increment() via ComponentClient with a fixed counter ID ("synthetic-record-counter") and the event type name.
- [x] T041 [US6] Add consumer counter endpoint to PulseEndpoint in src/main/java/com/example/api/PulseEndpoint.java — `GET /pulse/consumers/{counterId}` returns ConsumerCounter state
- [x] T042 [US6] Verify compiles with `mvn compile`

### Tests for User Story 6

- [x] T043 [US6] Add consumer integration test to PulseEndpointIntegrationTest in src/test/java/com/example/api/PulseEndpointIntegrationTest.java — create a record via POST, then use Awaitility to poll `GET /pulse/consumers/synthetic-record-counter` until eventCount > 0
- [x] T044 [US6] Verify all tests pass with `mvn verify`

**Checkpoint**: Consumer event processing observable via counter endpoint

---

## Phase 9: User Story 7 - Timed Action (Priority: P3)

**Goal**: Timed action that can be scheduled via endpoint and records its execution

**Independent Test**: Schedule a timer with short delay, wait, query — verify execution recorded.

### Implementation for User Story 7

- [x] T045 [US7] Create SyntheticTimedAction in src/main/java/com/example/application/SyntheticTimedAction.java — extends TimedAction, method `execute()` increments ConsumerCounterEntity with event type "timer-fired" via ComponentClient. Returns `effects().done()`.
- [x] T046 [US7] Add timed action endpoints to PulseEndpoint in src/main/java/com/example/api/PulseEndpoint.java — `POST /pulse/timers/{timerId}/schedule` accepts ScheduleTimerRequest(delaySeconds), schedules timer via TimerScheduler. `GET /pulse/timers/{timerId}` returns last execution info from ConsumerCounterEntity.
- [x] T047 [US7] Verify compiles with `mvn compile`

**Checkpoint**: Timed action schedulable and observable

---

## Phase 10: User Story 10 - Burst Request Generation (Priority: P2)

**Goal**: Endpoint that fires N parallel requests to a target component type

**Independent Test**: `POST /pulse/burst` with count=10 and target=key-value-entity — verify response shows 10 succeeded.

**Depends on**: US2, US3 (target entities must exist)

### Implementation for User Story 10

- [x] T048 [US10] Create BurstEndpoint in src/main/java/com/example/api/BurstEndpoint.java — `@HttpEndpoint("/pulse/burst")`, `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`. `POST /` accepts BurstRequest(target, count, delaySeconds). Validates count <= 100, delaySeconds <= 300. Fires count parallel requests using CompletableFuture.allOf() via ComponentClient to either SyntheticRecordEntity or SyntheticEntryEntity (based on target). Each request uses a UUID entity ID. Returns BurstResponse(target, requested, succeeded, failed, totalDurationMs). Define BurstRequest and BurstResponse as inner records.
- [x] T049 [US10] Verify compiles with `mvn compile`

### Tests for User Story 10

- [x] T050 [US10] Create BurstEndpointIntegrationTest in src/test/java/com/example/api/BurstEndpointIntegrationTest.java — extends TestKitSupport: test burst with small count (5) to ESE succeeds, test burst with count > 100 returns error, test burst with invalid target returns error. Use httpClient.
- [x] T051 [US10] Verify all tests pass with `mvn verify`

**Checkpoint**: Burst traffic generation working

---

## Phase 11: User Story 8 - OpenAPI Endpoint (Priority: P2)

**Goal**: Auto-generated OpenAPI spec served from the service

**Independent Test**: `curl http://localhost:9000/pulse/openapi.yaml` returns valid YAML.

### Implementation for User Story 8

- [x] T052 [US8] Verify akka-openapi-maven-plugin is configured correctly in pom.xml (from T001) and generates `target/classes/static-resources/openapi.yaml` during build
- [x] T053 [US8] Add OpenAPI endpoint to PulseEndpoint in src/main/java/com/example/api/PulseEndpoint.java — `GET /pulse/openapi.yaml` returns `HttpResponses.staticResource("openapi.yaml")`
- [x] T054 [US8] Verify compiles and OpenAPI spec generates with `mvn compile`

**Checkpoint**: OpenAPI spec auto-generated and served

---

## Phase 12: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, final validation, cleanup

- [x] T055 [P] Update README.md with project description, build/run instructions, and curl examples from quickstart.md
- [x] T056 Run full test suite with `mvn verify` and fix any failures
- [x] T057 Run quickstart.md validation — start service locally, execute all curl commands from quickstart.md, verify expected responses

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — BLOCKS all user stories
- **Phase 3 (US1 Health)**: Depends on Phase 2 — no story dependencies
- **Phase 4 (US2 ESE)**: Depends on Phase 2 — no story dependencies
- **Phase 5 (US3 KVE)**: Depends on Phase 2 — no story dependencies
- **Phase 6 (US4 View)**: Depends on Phase 2 + US2 (needs ESE events)
- **Phase 7 (US5 Workflow)**: Depends on Phase 2 + US3 (needs KVE for persist step)
- **Phase 8 (US6 Consumer)**: Depends on Phase 2 + US2 (needs ESE events)
- **Phase 9 (US7 Timed Action)**: Depends on Phase 2 + US6 (reuses ConsumerCounterEntity)
- **Phase 10 (US10 Burst)**: Depends on US2 + US3 (needs target entities)
- **Phase 11 (US8 OpenAPI)**: Depends on all endpoints being created (Phase 3-10)
- **Phase 12 (Polish)**: Depends on all phases

### User Story Dependencies

```
Phase 2 (Foundational)
├── US1 (Health)          ← independent
├── US2 (ESE)             ← independent
│   ├── US4 (View)        ← needs US2 events
│   ├── US6 (Consumer)    ← needs US2 events
│   │   └── US7 (Timed)   ← reuses ConsumerCounterEntity
│   └── US10 (Burst)      ← needs US2 + US3
├── US3 (KVE)             ← independent
│   ├── US5 (Workflow)    ← needs US3 for persist
│   └── US10 (Burst)      ← needs US2 + US3
└── US8 (OpenAPI)         ← needs all endpoints
```

### Parallel Opportunities

After Phase 2 completes, these can run in parallel:
- **US1** (Health), **US2** (ESE), **US3** (KVE) — all independent
- Once US2 done: **US4** (View) and **US6** (Consumer) can run in parallel
- Once US3 done: **US5** (Workflow) can start
- Once US2 + US3 done: **US10** (Burst) can start

---

## Parallel Example: After Phase 2

```
# These three stories can all start simultaneously:
US1: T012 → T013 → T014 → T015 → T016
US2: T017 → T018 → T019 → T020 → T021 → T022
US3: T023 → T024 → T025 → T026 → T027 → T028

# Once US2 completes, these can start in parallel:
US4: T029 → T030 → T031 → T032 → T033
US6: T039 → T040 → T041 → T042 → T043 → T044
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (domain layer)
3. Complete Phase 3: US1 (Health Check)
4. **STOP and VALIDATE**: `curl /pulse/health` returns 200 with persistence check
5. Deploy if ready — immediate value as a deployment probe

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. US1 (Health) → Deploy (probe available!)
3. US2 (ESE) + US3 (KVE) → Deploy (entities exercisable)
4. US4 (View) + US5 (Workflow) + US6 (Consumer) → Deploy (full component coverage)
5. US7 (Timed) + US10 (Burst) → Deploy (advanced features)
6. US8 (OpenAPI) → Deploy (API documentation)
7. Polish → Final release

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- US9 (Configurable Delay) is integrated into US2, US3, US5 — not a separate phase
- Tests are included per FR-012 and FR-013 requirements
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
