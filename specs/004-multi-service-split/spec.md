# Feature Specification: Multi-Service Project Split

**Feature Branch**: `004-multi-service-split`
**Created**: 2026-09-07
**Status**: Draft
**Input**: User description: "Restructure akka-pulse into a Maven multi-module project with multiple deployable services and a shared library module: pulse-common (reusable health check + SecretLoader), pulse-core (existing service), and pulse-peer (on-demand service for s2s validation). Root pom is an aggregator; each service builds its own image. Repo maps one-to-one to an Akka project with a versioned project descriptor. Key risk: component discovery from a dependency jar."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Shared health check library reused by the main service (Priority: P1)

As a platform tester, I want the repository restructured into one shared library and the existing service, where the health check capability (health endpoint plus a write/read state probe that reports the current region) lives in the shared library and is consumed by the service, so that I can validate that platform services can reuse components packaged in a library.

**Why this priority**: This is the structural foundation. Every other story builds on the multi-module layout, and library-based component reuse is the main platform feature under test. It also carries the highest technical risk (components discovered from a dependency, not the service's own code).

**Independent Test**: Build the repository, run the main service, and call its health endpoint. The health check must behave exactly as before while its implementation resides only in the shared library.

**Acceptance Scenarios**:

1. **Given** the restructured repository, **When** the full build runs, **Then** it produces one shared library artifact and one deployable main service, and all existing tests pass.
2. **Given** the main service is running, **When** the health endpoint is called, **Then** it performs the write/read probe and reports status and region exactly as the current service does.
3. **Given** the shared library, **When** searching the main service's own sources, **Then** no health check implementation is duplicated there.
4. **Given** the main service is running, **When** any pre-existing probe endpoint is called (entities, view, workflow, consumers, timers, topics, secrets, JWT, burst), **Then** it behaves identically to the single-module version, on the same paths.

---

### User Story 2 - On-demand peer service for service-to-service validation (Priority: P2)

As a platform tester, I want a second, minimal service that I deploy only when I need to validate service-to-service settings in an environment. It probes the main service in three ways: a direct request to the main service, consumption of an event stream the main service exposes to other services, and a request to a main-service endpoint that only the peer service is allowed to call. The peer reuses the shared library's health check, proving reuse in a second codebase.

**Why this priority**: Delivers the new testing capability (s2s validation) that motivated the split, but depends on the P1 structure being in place.

**Independent Test**: Deploy both services, call the peer's probe endpoints, and receive an explicit pass/fail result for each of the three s2s probes.

**Acceptance Scenarios**:

1. **Given** both services are deployed, **When** the peer's direct-call probe is invoked, **Then** it reports success including evidence from the main service's response.
2. **Given** both services are deployed and the main service has produced events, **When** the peer's stream probe is queried, **Then** it reports how many events it has consumed from the main service's cross-service stream.
3. **Given** both services are deployed, **When** the peer's restricted-endpoint probe is invoked, **Then** it succeeds, and **When** the same restricted endpoint on the main service is called from the internet, **Then** the request is denied.
4. **Given** only the main service is deployed, **When** its probes are exercised, **Then** everything works; the peer's absence has no effect on the main service.
5. **Given** the peer service is running, **When** its health endpoint is called, **Then** it responds with the same health check behavior as the main service, provided by the shared library.

---

### User Story 3 - Versioned project descriptor (Priority: P3)

As a platform tester, I want this repository to map one-to-one to a platform project, with a checked-in, versioned project descriptor that declares the project's services, so that the deployable shape of the environment evolves together with the code and can be applied declaratively.

**Why this priority**: Valuable for reproducible environments and for testing descriptor-driven deployment, but the services can be deployed manually without it.

**Independent Test**: Apply the checked-in descriptor to a project and verify the declared services reach a running state without manual per-service configuration.

**Acceptance Scenarios**:

1. **Given** the checked-in descriptor, **When** it is applied to a project, **Then** the main service is deployed and healthy.
2. **Given** the peer service is only needed on demand, **When** using the descriptor variant that includes the peer, **Then** both services deploy and the s2s probes pass.

---

### Edge Cases

- Component discovery from a library: if the platform does not discover components packaged in a dependency, the P1 approach is invalid. This must be validated first, before migrating anything else (primary risk).
- Peer not deployed: the main service's cross-service stream has no consumer and its restricted endpoint has no allowed caller. Neither condition may degrade the main service or its health.
- Restricted endpoint called by an unauthorized caller (internet or another service): the request must be denied with an access-denied result, not an error that pages an operator.
- Peer deployed before the main service, or main service restarted: the peer's probes must report clear failures (unreachable target) and recover without redeployment once the main service is up.
- Both services expose a health endpoint on the same path: acceptable, they are separate services; the health response must identify the service it came from.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The repository MUST build as a single build producing one shared library artifact and two independently deployable services (main and peer).
- **FR-002**: The shared library MUST provide the complete health check capability (health endpoint plus write/read state probe reporting status and region) so that any consuming service gets it without duplicating code.
- **FR-003**: The shared library MUST also provide the existing secret-loading utility used by the main service.
- **FR-004**: The main service MUST retain all existing probe capabilities and their API paths unchanged (entities, view, workflow, consumers, timed action, topic producer/consumer, secrets, JWT, burst).
- **FR-005**: The main service and the peer service MUST both obtain their health check from the shared library.
- **FR-006**: The peer service MUST provide three service-to-service probes against the main service, each reporting an explicit result: (a) a direct request/response call, (b) consumption of an event stream the main service exposes to other services, with an observable consumed count, (c) a call to a main-service endpoint restricted to the peer service only.
- **FR-007**: The main service MUST expose an endpoint that only the peer service is permitted to call; requests from the internet or other callers MUST be denied.
- **FR-008**: The main service MUST be fully functional when the peer service is not deployed; the peer MUST be deployable and removable at any time without affecting the main service.
- **FR-009**: The repository MUST contain a versioned project descriptor declaring the project's services, kept in sync with the code, supporting both the main-only and main-plus-peer deployment shapes.
- **FR-010**: Each service MUST build its own container image; the shared library MUST NOT be deployable on its own.
- **FR-011**: All existing automated tests MUST continue to pass after the restructuring, relocated to the module that owns the code they test.

### Key Entities

- **Health probe result**: status (up/degraded), region identity, evidence of the write/read round trip; identical shape for every service using the shared library.
- **S2S probe result**: probe type (direct call, stream consumption, restricted call), pass/fail, and supporting evidence (target response, consumed event count, or access decision).
- **Project descriptor**: the declarative definition of the project's services and their deployment shape, versioned with the repository.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the documented probe requests from the current README work unchanged against the restructured main service.
- **SC-002**: The health check is implemented exactly once, and both running services answer their health endpoint with the same behavior and response shape.
- **SC-003**: An operator can validate service-to-service settings in an environment by deploying the peer and making at most three requests, each returning an explicit pass/fail with evidence.
- **SC-004**: Removing the peer service leaves the main service passing all of its probes, including health.
- **SC-005**: A project can be brought to its declared shape (main-only or main-plus-peer) from the checked-in descriptor without manual per-service configuration.
- **SC-006**: A request from the internet to the restricted endpoint is denied 100% of the time, while the peer's restricted-call probe succeeds.

## Assumptions

- Module names follow the pulse-common / pulse-core / pulse-peer naming; final names may be adjusted during planning without changing scope.
- The peer exposes its s2s probes as its own HTTP endpoints, so an operator validates s2s by calling the peer directly.
- The cross-service stream carries the main service's existing synthetic record events; no new event types are introduced for it.
- The existing topic on/off and JWT on/off toggles keep their current semantics inside the main service; splitting messaging into its own service is explicitly out of scope (Option A of the split discussion).
- The one-to-one repo-to-project mapping means one descriptor (with a main-only and a main-plus-peer shape); multi-project or multi-repo layouts are out of scope.
- Component discovery from a dependency jar is assumed possible; validating this is the first implementation step, and if it fails the approach must be revisited before any migration.
