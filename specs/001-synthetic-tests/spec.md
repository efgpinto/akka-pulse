# Feature Specification: Synthetic Test Suite & Health Monitoring

**Feature Branch**: `001-synthetic-tests`
**Created**: 2026-06-07
**Status**: Draft
**Input**: User description: "Synthetic tests for all Akka component types, OpenAPI endpoint generation, health check probe, multi-environment deployment validation"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Health Check Probe (Priority: P1)

As an operations engineer, I want a dedicated health check endpoint that not only reports whether the service is running, but also performs a round-trip write/read to an entity, so that I can use it as a deep health probe that validates the persistence layer is functioning in any deployment environment.

**Why this priority**: Without a health check, there is no way to confirm the service is alive. A superficial "ping" check can pass while the persistence layer is broken. Writing to an entity and reading back confirms the full stack — service, runtime, and database — is operational.

**Independent Test**: Can be fully tested by sending a request to the health endpoint and verifying a successful response that includes both service metadata and confirmation that a persistence round-trip succeeded. Delivers immediate value as a deployment probe.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** a request is sent to the health endpoint, **Then** the endpoint writes a heartbeat to a fixed, well-known entity (overwriting the previous value) and reads it back, returning healthy status with a 200 status code
2. **Given** the service is running, **When** a request is sent to the health endpoint, **Then** the response includes the service name, version, current timestamp, and persistence check result
3. **Given** the persistence layer is unreachable, **When** a request is sent to the health endpoint, **Then** the response indicates unhealthy status with details about the failure
4. **Given** the service is not fully initialized, **When** a request is sent to the health endpoint, **Then** the response indicates unhealthy status

---

### User Story 2 - Event Sourced Entity Synthetic Test (Priority: P1)

As a platform engineer, I want a synthetic Event Sourced Entity that can be exercised through an endpoint, so that I can verify event sourcing, persistence, and metrics export are functioning correctly in any environment.

**Why this priority**: Event Sourced Entities are the most complex component type. Validating them confirms the core persistence and event journal infrastructure is working.

**Independent Test**: Can be tested by sending commands to create, update, and query the entity, then verifying state changes and event history.

**Acceptance Scenarios**:

1. **Given** no prior state exists, **When** a create command is sent with test data, **Then** the entity persists the creation event and returns the initial state
2. **Given** an entity exists, **When** an update command is sent, **Then** the entity persists the update event and the state reflects the change
3. **Given** an entity with multiple events, **When** the entity is queried, **Then** the current state reflects all applied events in order

---

### User Story 3 - Key Value Entity Synthetic Test (Priority: P1)

As a platform engineer, I want a synthetic Key Value Entity that can be exercised through an endpoint, so that I can verify simple state persistence is functioning correctly.

**Why this priority**: Key Value Entities are a fundamental component type, and validating them confirms basic state storage works.

**Independent Test**: Can be tested by setting and getting values through the endpoint.

**Acceptance Scenarios**:

1. **Given** no prior state exists, **When** a value is set, **Then** the entity stores it and returns confirmation
2. **Given** a value exists, **When** it is updated, **Then** the new value is persisted and returned on query
3. **Given** a value exists, **When** it is deleted, **Then** the entity returns to empty state

---

### User Story 4 - View Synthetic Test (Priority: P2)

As a platform engineer, I want a synthetic View that projects data from the Event Sourced Entity, so that I can verify view projections and queries work correctly.

**Why this priority**: Views validate the read-side projection pipeline, which depends on entities working first.

**Independent Test**: Can be tested by creating entities and querying the view to verify projected data appears.

**Acceptance Scenarios**:

1. **Given** an Event Sourced Entity has events, **When** the view is queried, **Then** the projected data is returned
2. **Given** multiple entities exist, **When** the view is queried with a filter, **Then** only matching entries are returned
3. **Given** an entity is updated, **When** the view is queried after a short delay, **Then** the view reflects the updated state

---

### User Story 5 - Workflow Synthetic Test (Priority: P2)

As a platform engineer, I want a synthetic Workflow that orchestrates a multi-step process, so that I can verify workflow execution, step transitions, and recovery work correctly.

**Why this priority**: Workflows validate the durable execution engine. They depend on entities but add orchestration complexity.

**Independent Test**: Can be tested by starting a workflow and verifying it transitions through all steps to completion.

**Acceptance Scenarios**:

1. **Given** a workflow is started with a valid command, **When** validation passes and the entity write succeeds, **Then** the workflow reaches completed status
2. **Given** a workflow is started with a "trigger failure" command, **When** the persist step fails, **Then** the workflow applies the compensation strategy and reaches a compensated status
3. **Given** a workflow is in progress, **When** its status is queried, **Then** the current step and state are returned
4. **Given** a workflow has completed or compensated, **When** its result is queried, **Then** the final outcome (success or compensation) is returned

---

### User Story 6 - Consumer Synthetic Test (Priority: P2)

As a platform engineer, I want a synthetic Consumer that reacts to entity events, so that I can verify event consumption and processing works correctly.

**Why this priority**: Consumers validate the event-driven messaging infrastructure between components.

**Independent Test**: Can be tested by producing events from an entity and verifying the consumer processes them (e.g., by updating a secondary entity or counter).

**Acceptance Scenarios**:

1. **Given** a consumer is subscribed to an entity, **When** the entity emits an event, **Then** the consumer processes the event
2. **Given** a consumer processes events, **When** the processing result is queried, **Then** it reflects all consumed events

---

### User Story 7 - Timed Action Synthetic Test (Priority: P3)

As a platform engineer, I want a synthetic Timed Action that can be triggered via an endpoint, so that I can verify timer scheduling and execution work correctly.

**Why this priority**: Timed Actions validate the scheduler infrastructure. Lower priority because they are less commonly used.

**Independent Test**: Can be tested by scheduling a timer and verifying it fires and executes the action.

**Acceptance Scenarios**:

1. **Given** a timed action is scheduled, **When** the scheduled time arrives, **Then** the action executes and records its execution
2. **Given** a timed action endpoint is called, **When** the timer is set with a short delay, **Then** the effect can be observed after the delay

---

### User Story 8 - OpenAPI Endpoint (Priority: P2)

As a developer or operations engineer, I want the service to expose an auto-generated OpenAPI specification endpoint, so that I can discover all available endpoints, their parameters, and responses without reading source code.

**Why this priority**: OpenAPI documentation enables tooling integration, client generation, and discoverability across environments.

**Independent Test**: Can be tested by requesting the OpenAPI spec endpoint and verifying it returns a valid OpenAPI document that describes all service endpoints.

**Acceptance Scenarios**:

1. **Given** the service is running, **When** the OpenAPI spec endpoint is requested, **Then** a valid OpenAPI 3.x document is returned
2. **Given** endpoints exist in the service, **When** the OpenAPI spec is retrieved, **Then** all synthetic test endpoints and the health endpoint are documented
3. **Given** the OpenAPI spec is retrieved, **When** it is loaded into an API tool, **Then** it renders correctly with endpoint descriptions

---

### User Story 9 - Configurable Processing Delay (Priority: P1)

As a platform engineer, I want to be able to send commands to entities and workflow steps with a parameter that specifies how long the processing should take (in seconds), so that I can simulate slow operations and trigger latency-based alerting.

**Why this priority**: Being able to simulate slow processing is essential for validating alerting rules and SLO configurations. Without this, there is no controlled way to test that monitoring catches degraded performance.

**Independent Test**: Can be tested by sending a command with a delay parameter (e.g., 5 seconds) and verifying the response takes at least that long.

**Acceptance Scenarios**:

1. **Given** an entity command is sent with a delay parameter of X seconds, **When** the command is processed, **Then** processing takes at least X seconds before returning
2. **Given** a workflow is started with a delay parameter of Y seconds on a step, **When** that step executes, **Then** the step takes at least Y seconds before transitioning
3. **Given** a command is sent with no delay parameter, **When** it is processed, **Then** it completes immediately (no artificial delay)
4. **Given** a delay parameter exceeds the maximum allowed value, **When** the command is sent, **Then** the system rejects it with an error

---

### User Story 10 - Burst Request Generation (Priority: P2)

As a platform engineer, I want an endpoint that can generate a small burst of requests to synthetic components, so that I can produce a spike in metrics and validate that monitoring dashboards and alerting detect the load change.

**Why this priority**: Burst generation enables testing of metrics pipelines, auto-scaling triggers, and rate-based alerting without needing external load generation tools.

**Independent Test**: Can be tested by calling the burst endpoint with a target component and count, then checking that the expected number of operations were performed.

**Acceptance Scenarios**:

1. **Given** a burst request specifying a target component and count N, **When** the burst endpoint is called, **Then** N requests are sent to the target component and a summary is returned
2. **Given** a burst request with a delay parameter per request, **When** the burst is executed, **Then** each request in the burst uses the specified delay
3. **Given** a burst count exceeds the maximum allowed, **When** the burst endpoint is called, **Then** the system rejects it with an error

---

### Edge Cases

- What happens when an entity is accessed before it has been created? The system should return a clear "not found" or empty state response.
- What happens when a workflow step times out? The recovery strategy should apply and the workflow should not hang indefinitely.
- What happens when the health endpoint is called during service startup? It should return an appropriate non-200 response until ready.
- What happens when the OpenAPI endpoint is called with an unsupported content type? It should return the spec in JSON format by default.
- What happens when a delay parameter is negative or zero? The system should treat it as no delay (immediate processing).
- What happens when a burst is in progress and another burst is requested? The system should allow concurrent bursts up to the maximum count per burst.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a health check endpoint that performs a write/read round-trip to an entity and returns service status, name, version, timestamp, and persistence check result
- **FR-002**: System MUST include a synthetic Event Sourced Entity with create, update, and query commands
- **FR-003**: System MUST include a synthetic Key Value Entity with set, get, and delete operations
- **FR-004**: System MUST include a synthetic View that projects data from the Event Sourced Entity
- **FR-005**: System MUST include a synthetic Workflow with a validate-then-persist pattern (step 1 validates, step 2 writes to entity) with compensation on failure. The workflow MUST accept different commands, including one that deliberately triggers a failure to exercise the recovery/compensation path
- **FR-006**: System MUST include a synthetic Consumer that reacts to Event Sourced Entity events
- **FR-007**: System MUST include a synthetic Timed Action that can be triggered via endpoint
- **FR-008**: System MUST expose an auto-generated OpenAPI specification endpoint using the Maven OpenAPI plugin
- **FR-009**: System MUST expose HTTP endpoints for exercising each synthetic component
- **FR-010**: All endpoints MUST be accessible from the internet (public ACL) for external monitoring tools
- **FR-011**: Each synthetic component MUST produce observable side effects (state changes, event counts) that can be verified through query endpoints
- **FR-012**: System MUST include unit tests for all entities (Event Sourced and Key Value)
- **FR-013**: System MUST include integration tests for views, endpoints, and workflows
- **FR-014**: Entity commands and workflow steps MUST accept an optional delay parameter (in seconds) that causes processing to take the specified duration before completing
- **FR-015**: Commands with no delay parameter MUST complete immediately with no artificial delay
- **FR-016**: The system MUST enforce a maximum allowed delay of 300 seconds (5 minutes) and reject requests that exceed it
- **FR-017**: System MUST provide a burst endpoint that accepts a target component type, a request count, and an optional per-request delay, and fires that many requests in parallel to the target component, returning a summary when all complete
- **FR-018**: The system MUST enforce a maximum burst count of 100 requests and reject requests that exceed it

### Key Entities

- **SyntheticRecord**: Test data record used by the Event Sourced Entity. Key attributes: record ID, name, value, status, timestamp, version counter.
- **SyntheticEntry**: Simple key-value test entry used by the Key Value Entity. Key attributes: entry ID, data payload, last updated timestamp.
- **SyntheticViewEntry**: Read-side projection of SyntheticRecord data. Key attributes: record ID, name, value, status (projected from entity events).
- **WorkflowState**: Tracks synthetic workflow progress. Key attributes: workflow ID, current step, status, result data.
- **ConsumerCounter**: Tracks events consumed by the synthetic consumer. Key attributes: counter ID, event count, last event type, last event timestamp.

## Clarifications

### Session 2026-06-07

- Q: Should the service include explicit metrics validation endpoints? → A: No — rely on platform observability. Synthetic tests exercise components to generate metrics; metrics verification is done through the Akka platform's built-in monitoring tools.
- Q: Should the health check use a fixed entity ID or create a new record per call? → A: Fixed entity ID — always write/read to the same heartbeat entity, overwriting the previous value. Avoids unbounded data growth.
- Q: What should the synthetic workflow steps do? → A: Validate-then-persist pattern (step 1 validates, step 2 writes to entity, with compensation on failure). The workflow must accept different commands, including one that deliberately triggers a failure to exercise the recovery/compensation path.
- Q: Should the burst endpoint fire requests sequentially or in parallel? → A: Parallel — fire all requests concurrently, returning a summary when all complete. Creates a realistic traffic spike for testing alerting and auto-scaling.
- Q: What should the maximum allowed delay and burst count be? → A: Max delay 300 seconds (5 min), max burst 100 requests.

## Assumptions

- The Maven OpenAPI plugin will be configured in `pom.xml` to auto-generate the OpenAPI spec from annotated endpoints.
- The synthetic components are intentionally simple and do not model real business logic; their purpose is infrastructure validation.
- All synthetic endpoints share a common `/pulse` path prefix for easy identification and routing.
- The health endpoint will be at `/pulse/health` for consistency with the service name.
- Service version will be derived from the Maven project version at build time.
- The Consumer will track event counts via a Key Value Entity to make consumption observable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The health endpoint responds within 1 second and correctly reports service status in all deployment environments
- **SC-002**: All synthetic component types (Event Sourced Entity, Key Value Entity, View, Workflow, Consumer, Timed Action) can be exercised through their respective endpoints and produce verifiable results
- **SC-003**: The OpenAPI specification endpoint returns a valid, complete document describing all service endpoints
- **SC-004**: The service can be deployed to a new environment and an operator can confirm all components are working within 5 minutes by exercising the synthetic endpoints
- **SC-005**: All unit tests pass for entity components and all integration tests pass for views, endpoints, and workflows
- **SC-006**: An operator can run a single health check request to determine if the service is ready to receive traffic
