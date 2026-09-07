# Tasks: Multi-Service Project Split

**Input**: Design documents from `/specs/004-multi-service-split/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included (constitution III and FR-011 require them).

**Organization**: Grouped by user story. US1 begins with the discovery spike (T005–T007) that
gates the whole approach — if the library-provided endpoint is not discovered, stop and revisit
research R1.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup (repo restructuring)

**Purpose**: Multi-module skeleton with the existing service relocated unchanged.

- [x] T001 Create aggregator root `pom.xml` (packaging pom, no parent, modules: pulse-common, pulse-core); `git mv` existing `src/` to `pulse-core/src/` and move current service pom to `pulse-core/pom.xml` with artifactId `pulse-core` (keep akka-javasdk-parent, openapi plugin, static resources)
- [x] T002 Create `pulse-common/pom.xml`: plain jar, `io.akka:akka-javasdk:3.6.3` dependency, maven-compiler-plugin with `-parameters`, `-Aakka.javasdk.groupId`/`-Aakka.javasdk.artifactId` args and `ComponentValidationProcessor`+`ComponentAnnotationProcessor` via processor path `io.akka:akka-javasdk-annotation-processor:3.6.3` (research R2), plus test deps (junit, assertj, testkit as needed)
- [x] T003 Add pulse-common as dependency of pulse-core in `pulse-core/pom.xml`
- [x] T004 Verify baseline: `mvn verify` from root passes with all existing tests green (service functionally unchanged)

**Checkpoint**: multi-module build green, service identical to pre-split.

---

## Phase 2: User Story 1 — Shared health check library (P1) 🎯 MVP

**Goal**: Health check lives only in pulse-common; pulse-core serves it from the jar; all existing
probe paths unchanged.

**Independent Test**: `mvn verify`; core integration test hits `/pulse/health` and gets the
documented response while no health implementation exists in pulse-core sources.

### Discovery spike (gates everything — research R1)

- [x] T005 [US1] Move `HealthCheckEntry` to `pulse-common/src/main/java/com/example/common/domain/HealthCheckEntry.java` and `HealthCheckEntity` to `pulse-common/src/main/java/com/example/common/application/HealthCheckEntity.java` (package updates only)
- [x] T006 [US1] Create `pulse-common/src/main/java/com/example/common/api/HealthEndpoint.java` serving `GET /pulse/health` per contracts/health-api.md (serviceName from config `pulse.health.service-name`, version from `pulse.health.version`); add `pulse-common/src/main/resources/reference.conf` (default `pulse.health.version = "1.0-SNAPSHOT"`); remove the health block from `pulse-core/src/main/java/com/example/api/PulseEndpoint.java`; set `pulse.health.service-name = "pulse-core"` in `pulse-core/src/main/resources/application.conf`
- [x] T007 [US1] SPIKE GATE: build and confirm `pulse-common.jar` contains `META-INF/akka-javasdk-components_com.example_pulse-common.conf` listing HealthEndpoint + HealthCheckEntity; run core integration tests (existing `PulseEndpointIntegrationTest` health case or add one) proving `/pulse/health` is served from the library. If discovery fails → STOP, revisit research R1

### Complete the library

- [x] T008 [P] [US1] Move `HealthCheckEntityTest` to `pulse-common/src/test/java/com/example/common/application/HealthCheckEntityTest.java`
- [x] T009 [P] [US1] Move `SecretLoader` to `pulse-common/src/main/java/com/example/common/application/SecretLoader.java` and `SecretLoaderTest` to `pulse-common/src/test/`; update import in `pulse-core/src/main/java/com/example/api/SecretEndpoint.java`
- [x] T010 [US1] Full regression from root: `mvn verify` green; grep confirms no health/secret-loader implementation remains in pulse-core sources (SC-001, SC-002)

**Checkpoint**: MVP — restructured project, library reuse proven in pulse-core.

---

## Phase 3: User Story 2 — On-demand peer service for s2s validation (P2)

**Goal**: pulse-peer with three probes (direct, stream, restricted); core exposes the stream and
the restricted endpoint; core unaffected when peer absent.

**Independent Test**: `mvn verify` (peer ITs mock the upstream stream); locally per quickstart.md.

### Core s2s surface

- [ ] T011 [P] [US2] Create `PulseStreamEvent` (sealed, `@TypeName` pulse-record-created/pulse-record-updated per data-model.md) in `pulse-common/src/main/java/com/example/common/domain/PulseStreamEvent.java`
- [ ] T012 [US2] Create `SyntheticRecordStreamProducer` in `pulse-core/src/main/java/com/example/application/SyntheticRecordStreamProducer.java`: `@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)` + `@Produce.ServiceStream(id = "synthetic-records")` + `@Acl(allow = @Acl.Matcher(service = "*"))`, mapping internal events to `PulseStreamEvent`
- [ ] T013 [US2] Create `InternalPingEndpoint` in `pulse-core/src/main/java/com/example/api/InternalPingEndpoint.java`: `GET /pulse/internal/ping`, `@Acl(allow = @Acl.Matcher(service = "pulse-peer"))`, response per contracts/core-internal-api.md
- [ ] T014 [US2] Core tests: stream-capture integration test for the producer transform (eventing testkit, consuming-producing.html.md pattern) in `pulse-core/src/test/java/com/example/application/SyntheticRecordStreamProducerIntegrationTest.java`; basic response test for InternalPingEndpoint

### Peer service

- [ ] T015 [US2] Create `pulse-peer/pom.xml` (parent akka-javasdk-parent, dep pulse-common) and `pulse-peer/src/main/resources/application.conf` (`pulse.health.service-name = "pulse-peer"`); add module to root pom
- [ ] T016 [P] [US2] Create `StreamCounter` in `pulse-peer/src/main/java/com/example/peer/domain/StreamCounter.java` and `StreamCounterEntity` (KVE id `stream-counter`, increment/get) in `pulse-peer/src/main/java/com/example/peer/application/StreamCounterEntity.java`
- [ ] T017 [US2] Create `StreamProbeConsumer` in `pulse-peer/src/main/java/com/example/peer/application/StreamProbeConsumer.java`: `@Consume.FromServiceStream(service = "pulse-core", id = "synthetic-records")`, increments StreamCounterEntity
- [ ] T018 [US2] Create `PeerProbeEndpoint` in `pulse-peer/src/main/java/com/example/peer/api/PeerProbeEndpoint.java`: `/peer/probes/direct`, `/peer/probes/stream`, `/peer/probes/restricted` per contracts/peer-probes-api.md, using `HttpClientProvider.httpClientFor("pulse-core")`; failures return structured `passed=false`, never 5xx (R7)
- [ ] T019 [US2] Peer integration tests in `pulse-peer/src/test/java/com/example/peer/`: (a) mock upstream stream (testkit `FromServiceStream` mock) → stream probe reports count; (b) direct/restricted probes return structured FAIL when pulse-core unreachable; (c) `/pulse/health` served with serviceName `pulse-peer` (library reuse, SC-002)
- [ ] T020 [US2] Full build from root: `mvn verify` green across all three modules

**Checkpoint**: US1 + US2 complete; peer validated against mocked core; core untouched when peer absent.

---

## Phase 4: User Story 3 — Versioned project descriptor (P3)

**Goal**: Checked-in descriptor with main-only and main-plus-peer shapes.

**Independent Test**: descriptors validate; applying `project-core.yaml` yields a healthy pulse-core.

- [ ] T021 [US3] Derive descriptor schema from the current project (`akka project export` or docs) and create `deploy/project-core.yaml` (pulse-core only) and `deploy/project-full.yaml` (pulse-core + pulse-peer), image tags as documented placeholders (R8)
- [ ] T022 [US3] Validate both descriptors (akka project validate if available; otherwise structural review against export output) and document apply/tear-down flow in the descriptors' header comments

**Checkpoint**: deployment shape versioned with the code.

---

## Phase 5: Polish & Cross-Cutting

- [ ] T023 [P] Update `README.md`: multi-module layout, per-module build/run commands, peer probe endpoints, descriptor-based deploy (replace `akka service deploy akka-pulse ...`), service naming pulse-core/pulse-peer
- [ ] T024 [P] Sweep remaining references to single-module layout (`docs/*.md`, `.github/workflows/*` paths like `src/`, snyk scan paths, `CLAUDE.md`/`AGENTS.md` if they reference build commands) and fix
- [ ] T025 Run quickstart.md local validation: start pulse-core (`cd pulse-core && mvn compile exec:java`), curl `/pulse/health` and one pre-split probe; record results in the feature dir

---

## Dependencies & Execution Order

- Phase 1 (T001–T004) blocks everything.
- T005→T006→T007 sequential; T007 is the gate. T008/T009 parallel after T007; T010 last in US1.
- US2 needs US1 complete (library exists). T011 before T012/T017; T015 before T016–T019; T014 parallel with peer tasks; T020 last.
- US3 (T021–T022) independent of US2 code but listed after; only needs module/service names fixed (post T015).
- Polish after all stories.

### Parallel opportunities

- T008 + T009 (different modules/files)
- T011 + T013 (common vs core files); T014 + T016–T018 (core tests vs peer code)
- T023 + T024 (different docs)

## Implementation Strategy

MVP = Phase 1 + US1 (T001–T010): proves the split and the library-reuse feature. US2 adds the s2s
capability, US3 the descriptor. Stop points at every checkpoint; T007 is a hard gate.
