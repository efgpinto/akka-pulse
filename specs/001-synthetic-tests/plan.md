# Implementation Plan: Synthetic Test Suite & Health Monitoring

**Branch**: `001-synthetic-tests` | **Date**: 2026-06-07 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-synthetic-tests/spec.md`

## Summary

Build a synthetic test suite service (akka-pulse) that includes one instance of every Akka component type — Event Sourced Entity, Key Value Entity, View, Workflow, Consumer, Timed Action — plus a deep health check endpoint and burst traffic generator. All components support configurable processing delays for alerting validation. An auto-generated OpenAPI spec documents all endpoints. The service is designed for multi-environment deployment to validate platform infrastructure.

## Technical Context

**Language/Version**: Java 21, Akka SDK 3.6.0
**Primary Dependencies**: Akka Java SDK (`akka-javasdk-parent` 3.6.0), Akka OpenAPI Maven plugin (`sh.oso:akka-openapi-maven-plugin`)
**Storage**: Akka SDK built-in persistence (event journal for ESE, key-value store for KVE)
**Testing**: JUnit 5, AssertJ, Awaitility, Akka TestKit (`EventSourcedTestKit`, `KeyValueEntityTestKit`, `TestKitSupport`)
**Target Platform**: Akka Platform (cloud-deployed containerized service)
**Project Type**: Web service (Akka SDK)
**Performance Goals**: Health endpoint < 1 second; burst up to 100 parallel requests
**Constraints**: Max delay 300s, max burst 100 requests, public ACL on all endpoints
**Scale/Scope**: Single service, 10 user stories, ~15 source files + ~8 test files

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Akka SDK First | PASS | All components use Akka SDK primitives. Only external dependency is the OpenAPI Maven plugin (build-time only, no runtime dep). |
| II. Design Principles | PASS | Domain logic in `domain` package (records, events), components in `application`, endpoints in `api`. Each component has single responsibility. Descriptive naming (SyntheticRecordEntity, HealthCheckEntity, etc.). |
| III. Test Coverage | PASS | Unit tests for both entity types, integration tests for view, workflow, endpoints, consumer. Spec includes FR-012 and FR-013. |
| IV. Simplicity | PASS | Minimal synthetic components — no unnecessary abstractions. Each component does the simplest thing to validate its type. |

**External dependency justification**: `sh.oso:akka-openapi-maven-plugin` is a build-time-only Maven plugin that auto-generates OpenAPI specs from Akka HTTP endpoint annotations. This cannot be achieved with Akka SDK alone. It has no runtime footprint.

## Project Structure

### Documentation (this feature)

```text
specs/001-synthetic-tests/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Research findings
├── data-model.md        # Entity and view data model
├── quickstart.md        # Usage examples and curl commands
├── contracts/
│   └── http-api.md      # HTTP API contract
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
src/main/java/com/example/
├── domain/
│   ├── SyntheticRecord.java            # ESE state record
│   ├── SyntheticRecordEvent.java       # ESE sealed event interface
│   ├── SyntheticEntry.java             # KVE state record
│   ├── HealthCheckEntry.java           # Health KVE state record
│   ├── ConsumerCounter.java            # Consumer counter KVE state record
│   └── SyntheticWorkflowState.java     # Workflow state record
├── application/
│   ├── SyntheticRecordEntity.java      # Event Sourced Entity
│   ├── SyntheticEntryEntity.java       # Key Value Entity
│   ├── HealthCheckEntity.java          # Health check KV Entity (fixed ID)
│   ├── ConsumerCounterEntity.java      # Consumer counter KV Entity
│   ├── SyntheticRecordView.java        # View projecting ESE events
│   ├── SyntheticWorkflow.java          # Validate-then-persist workflow
│   ├── SyntheticEventConsumer.java     # Consumer reacting to ESE events
│   └── SyntheticTimedAction.java       # Timed action
└── api/
    ├── PulseEndpoint.java              # Main endpoint (entities, views, health)
    ├── WorkflowEndpoint.java           # Workflow endpoints
    └── BurstEndpoint.java              # Burst traffic generator endpoint

src/test/java/com/example/
├── domain/
│   └── SyntheticRecordTest.java        # Domain logic unit tests
├── application/
│   ├── SyntheticRecordEntityTest.java  # ESE unit test (EventSourcedTestKit)
│   ├── SyntheticEntryEntityTest.java   # KVE unit test (KeyValueEntityTestKit)
│   └── SyntheticRecordViewIntegrationTest.java  # View integration test
└── api/
    ├── PulseEndpointIntegrationTest.java       # Endpoint integration test
    ├── WorkflowEndpointIntegrationTest.java     # Workflow integration test
    └── BurstEndpointIntegrationTest.java        # Burst endpoint integration test

src/main/resources/
└── (OpenAPI spec auto-generated to target/classes/static-resources/ at build time)
```

**Structure Decision**: Standard Akka SDK single-service layout with `domain` / `application` / `api` package separation per constitution and AGENTS.md guidelines.

## Component Dependency Graph

```
                    ┌─────────────────┐
                    │  PulseEndpoint   │
                    └────┬───┬───┬────┘
                         │   │   │
          ┌──────────────┘   │   └──────────────┐
          ▼                  ▼                   ▼
┌──────────────────┐ ┌──────────────┐ ┌──────────────────┐
│SyntheticRecord   │ │SyntheticEntry│ │HealthCheckEntity │
│Entity (ESE)      │ │Entity (KVE)  │ │(KVE, fixed ID)   │
└────┬────┬────────┘ └──────────────┘ └──────────────────┘
     │    │
     │    └──────────────────┐
     ▼                       ▼
┌──────────────────┐ ┌──────────────────┐
│SyntheticRecord   │ │SyntheticEvent    │
│View              │ │Consumer          │
└──────────────────┘ └───────┬──────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ConsumerCounter   │
                    │Entity (KVE)      │
                    └──────────────────┘

┌──────────────────┐         ┌──────────────────┐
│WorkflowEndpoint  │────────▶│SyntheticWorkflow │──step2──▶ SyntheticEntryEntity
└──────────────────┘         └──────────────────┘

┌──────────────────┐
│BurstEndpoint     │──parallel──▶ SyntheticRecordEntity / SyntheticEntryEntity
└──────────────────┘

PulseEndpoint ──timer──▶ SyntheticTimedAction ──writes──▶ ConsumerCounterEntity (reused for tracking)
```

## Implementation Order

Based on dependency graph and user story priorities:

1. **Domain layer** — All state records and events (no dependencies)
2. **SyntheticRecordEntity** (ESE) — Core entity, most components depend on it
3. **SyntheticRecordEntity unit tests**
4. **SyntheticEntryEntity** (KVE) — Simple entity, used by workflow and health
5. **SyntheticEntryEntity unit tests**
6. **HealthCheckEntity** (KVE) — Dedicated health entity
7. **SyntheticRecordView** — Depends on ESE events
8. **ConsumerCounterEntity** (KVE) — Tracks consumer events
9. **SyntheticEventConsumer** — Depends on ESE + ConsumerCounterEntity
10. **SyntheticWorkflow** — Depends on SyntheticEntryEntity
11. **SyntheticTimedAction** — Standalone
12. **PulseEndpoint** — Depends on all entities + view + health
13. **WorkflowEndpoint** — Depends on workflow
14. **BurstEndpoint** — Depends on entities
15. **Integration tests** — Depends on all above
16. **OpenAPI plugin configuration** — pom.xml + static resource serving
17. **README update** — Documentation

## Complexity Tracking

No constitution violations. No complexity justifications needed.
