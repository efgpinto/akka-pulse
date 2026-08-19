# Implementation Plan: Multi-Region Test Scenarios

**Branch**: `002-multi-region-test-service` | **Date**: 2026-08-05 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-multi-region-test-service/spec.md`

## Summary

Extend the existing akka-pulse synthetic test suite to validate multi-region
behavior: replication of entities/views/workflows, consumer idempotency, and
cross-region topic publishing. The existing components are reused as-is. Three
things are added: (1) region identity in the health round-trip, (2) a topic
producer that publishes record events once per event using origin-based filtering,
and (3) a downstream topic consumer plus counter so duplicate publishing is
observable. Most multi-region validation is operational (two-region deploy);
automated tests cover the new single-region behavior.

## Technical Context

**Language/Version**: Java 21, Akka SDK 3.6.2 (`akka-javasdk-parent` 3.6.2)
**Primary Dependencies**: Akka Java SDK; Akka eventing (topics) with a project-level
message broker (Kafka or Google Pub/Sub) for deployed runs; existing OpenAPI Maven plugin
**Storage**: Akka SDK persistence — event journal (ESE) and key-value store (KVE),
replicated across regions in replicated-reads mode
**Testing**: JUnit 5, AssertJ, Awaitility, Akka TestKit (`TestKitSupport`,
`KeyValueEntityTestKit`, `EventingTestKit` with `withTopicOutgoingMessages` /
`withTopicIncomingMessages`)
**Target Platform**: Akka Platform, deployed to two regions
**Project Type**: Web service (Akka SDK), single project
**Performance Goals**: Replication to secondary region < 5s (SC-001); reads < 500ms (SC-002)
**Constraints**: HTTP endpoints have no region API (region read via KVE `selfRegion()`);
publish mode is build-time config; replicated-reads mode only
**Scale/Scope**: 2 regions, hundreds–low thousands of records; ~4 new source files,
2 changed files, ~3 new/updated tests

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Akka SDK First | PASS | Topic publishing uses built-in Akka eventing (`@Produce.ToTopic`, `@Consume.FromTopic`). No third-party client code. The message broker is external infrastructure required by Akka eventing — justified below. |
| II. Design Principles | PASS | New message/state records in `domain`; producer/consumer/entity in `application`; endpoint changes in `api`. Descriptive names (SyntheticTopicProducer, TopicMessageCounter). Dedicated topic counter keeps single-responsibility. |
| III. Test Coverage | PASS | New tests: topic producer (outgoing mock), topic consumer + counter (incoming mock), health region field. Cross-region behavior validated operationally (documented gap, not a coverage regression). |
| IV. Simplicity | PASS | Reuses existing components. Adds only what FR-007/008/010/014 require. Origin filter is a one-line `hasLocalOrigin()` guard. |

**External dependency justification**: A message broker (Kafka or Google Pub/Sub)
is required for FR-007/FR-010 topic scenarios. This is Akka's first-class eventing
integration, not a third-party library added to the code. It is configured at the
project level (`akka projects config set broker`) and mocked in tests via
`EventingTestKit` (no broker needed for tests). Post-design re-check: unchanged — PASS.

## Project Structure

### Documentation (this feature)

```text
specs/002-multi-region-test-service/
├── plan.md                       # This file
├── spec.md                       # Feature specification
├── research.md                   # Phase 0 findings
├── data-model.md                 # Additions/changes to the data model
├── quickstart.md                 # Multi-region + topic validation procedures
├── contracts/
│   ├── http-api-additions.md     # Health region field, topic-counter endpoint
│   └── topic-messaging.md        # Topic producer/consumer contract
└── checklists/
    └── requirements.md           # Spec quality checklist
```

### Source Code (repository root)

```text
src/main/java/com/example/
├── domain/
│   ├── HealthCheckEntry.java          # CHANGED: add `region` field
│   ├── SyntheticTopicMessage.java     # NEW: topic message payload (@TypeName)
│   └── TopicMessageCounter.java       # NEW: topic counter state record
├── application/
│   ├── HealthCheckEntity.java         # CHANGED: read/store commandContext().selfRegion()
│   ├── SyntheticTopicProducer.java    # NEW: Consumer + @Produce.ToTopic (origin filter)
│   ├── SyntheticTopicConsumer.java    # NEW: @Consume.FromTopic → counter
│   └── TopicMessageCounterEntity.java # NEW: KVE tracking topic messages
├── api/
│   └── PulseEndpoint.java             # CHANGED: health region field; GET /pulse/topic-counter/{id}
└── Bootstrap.java                     # CHANGED: provide PulseTopicSettings (publish-mode) via DI

src/test/java/com/example/
├── application/
│   ├── SyntheticTopicProducerIntegrationTest.java   # NEW: outgoing topic mock
│   ├── SyntheticTopicConsumerIntegrationTest.java   # NEW: incoming topic mock → counter
│   └── HealthCheckEntityTest.java                    # NEW/UPDATED: region field
└── api/
    └── PulseEndpointIntegrationTest.java             # UPDATED: health region, topic-counter

src/main/resources/
└── application.conf                   # CHANGED: pulse.topic.publish-mode (+ optional dev-mode eventing)
```

**Structure Decision**: Keep the existing Akka SDK single-service layout
(`domain` / `application` / `api`). Additions slot into the same packages.

## Component Dependency Graph

```
SyntheticRecordEntity ──events (replicated per region)──▶ SyntheticTopicProducer
                                                            │  (origin-only: publish iff hasLocalOrigin)
                                                            ▼  @Produce.ToTopic("synthetic-record-events")
                                                        [ topic ]
                                                            │  @Consume.FromTopic
                                                            ▼
                                                  SyntheticTopicConsumer ──▶ TopicMessageCounterEntity

PulseEndpoint ──write/read──▶ HealthCheckEntity (reads commandContext().selfRegion())
PulseEndpoint ──GET topic-counter──▶ TopicMessageCounterEntity

Bootstrap ──provides──▶ PulseTopicSettings (publish-mode from application.conf) ──▶ SyntheticTopicProducer
```

## Implementation Order

Follows the incremental workflow in CLAUDE.md — one component + its test at a time,
stop for review between steps.

1. **Domain** — `SyntheticTopicMessage`, `TopicMessageCounter`; change `HealthCheckEntry` (+region)
2. **HealthCheckEntity** (changed) + `HealthCheckEntityTest` — region via `selfRegion()`
3. **PulseEndpoint health** (changed) — surface `region` in response
4. **TopicMessageCounterEntity** (KVE) + unit test
5. **PulseTopicSettings + Bootstrap DI** — read `pulse.topic.publish-mode`
6. **SyntheticTopicProducer** + `SyntheticTopicProducerIntegrationTest` (outgoing mock)
7. **SyntheticTopicConsumer** + `SyntheticTopicConsumerIntegrationTest` (incoming mock → counter)
8. **PulseEndpoint topic-counter** endpoint (changed) + endpoint integration test
9. **application.conf** — publish-mode default `origin-only`; document dev-mode eventing
10. **README / docs** — multi-region + topic curl examples; link quickstart

## Risks & Known Gaps

| Risk | Detail | Mitigation |
|------|--------|------------|
| Timed Action region behavior (FR-006) | No documented multi-region guarantee for Timed Actions | Treat AL-9 as observation on a real deploy; do not assert a guarantee |
| Origin-region failure before publish (DI-5 / US6 #4) | `origin-only` loses a message if origin region dies pre-publish | Documented limitation; scenario reveals it. `every-region` mode avoids loss but duplicates |
| Endpoint has no region API | Health endpoint cannot read region directly | Region read inside `HealthCheckEntity` (KVE) and returned to the endpoint |
| Cross-region tests | TestKit is single-region; origin filtering can't be fully unit-tested | Automated tests cover single-region publish/consume; cross-region validated via quickstart |
| Broker required for deployed topics | Topics need a project broker configured | Documented in quickstart; tests use `EventingTestKit` (no broker) |

## Complexity Tracking

| Decision | Why Needed | Simpler Alternative Rejected Because |
|----------|------------|--------------------------------------|
| New `TopicMessageCounter` entity | Must track `lastOriginRegion` to observe per-region duplicates | Reusing `ConsumerCounter` cannot record origin region |
| Message broker (external infra) | FR-007/FR-010 topic scenarios | No in-SDK cross-service topic mechanism; broker is Akka's supported eventing path |

No constitution violations requiring justification beyond the external-dependency note above.
