# Implementation Plan: Multi-Service Project Split

**Branch**: `004-multi-service-split` | **Date**: 2026-09-07 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-multi-service-split/spec.md`

## Summary

Restructure the single-module akka-pulse service into a Maven multi-module project:
`pulse-common` (library jar: reusable health check endpoint + entity, SecretLoader, shared public
stream event type), `pulse-core` (the existing service, unchanged API, health from the library),
and `pulse-peer` (minimal on-demand service with three s2s probes: direct HTTP call, service-stream
consumption, ACL-restricted call). Add a versioned project descriptor with main-only and
main-plus-peer shapes. The enabling mechanism — SDK component discovery from dependency jars via
merged `META-INF/akka-javasdk-components_*.conf` descriptors — was verified in research (R1) and is
re-validated as the first implementation step.

## Technical Context

**Language/Version**: Java 21 (as per akka-javasdk-parent 3.6.3)
**Primary Dependencies**: Akka SDK 3.6.3 (`io.akka:akka-javasdk`, `akka-javasdk-parent`,
`akka-javasdk-annotation-processor`), akka-openapi-maven-plugin (core only)
**Storage**: Akka platform persistence (event journal + key-value store) via SDK components
**Testing**: JUnit 5, AssertJ, Akka TestKit (`TestKitSupport`, entity test kits, eventing test kit)
**Target Platform**: Akka Agentic Platform (deployed), local dev-mode runtime
**Project Type**: Maven multi-module — 1 library + 2 deployable services
**Performance Goals**: N/A (synthetic test service; behavior parity with current service)
**Constraints**: Existing `/pulse/*` API paths unchanged (SC-001); core fully functional without
peer (FR-008); library must not be deployable (FR-010); no broker dependency in peer (s2s stream is
brokerless)
**Scale/Scope**: ~30 existing classes relocated/split, ~8 new classes, 3 poms, 2 descriptor files

## Constitution Check

*GATE evaluated pre-Phase 0 and re-checked post-Phase 1: PASS*

- **I. Akka SDK First**: PASS. All new components are SDK primitives (Consumer with
  `@Produce.ServiceStream`, `@Consume.FromServiceStream` consumer, KVE counter, HTTP endpoints,
  `HttpClientProvider` for s2s). No new external dependencies. The one build deviation — pulse-common
  not using `akka-javasdk-parent` — is justified: the parent binds docker/exec for deployables, wrong
  for a library (research R2); the library still uses the SDK's own annotation processor.
- **II. Design Principles**: PASS. Domain records stay framework-free; endpoints define their own
  response records; new components are small and single-purpose (`HealthEndpoint`,
  `SyntheticRecordStreamProducer`, `InternalPingEndpoint`, `StreamProbeConsumer`,
  `PeerProbeEndpoint`); names are domain-aligned.
- **III. Test Coverage**: PASS. Tests move with their code (R10); new behavior (stream producer
  transform, peer probes, library-served health) gets new unit/integration tests.
- **IV. Simplicity**: PASS. Option A split only (no messaging service); peer holds the minimum for
  its three probes; no speculative extension points in the library.

## Project Structure

### Documentation (this feature)

```text
specs/004-multi-service-split/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── health-api.md
│   ├── core-internal-api.md
│   └── peer-probes-api.md
└── tasks.md             # Phase 2 output (/akka:tasks — NOT created by /akka:plan)
```

### Source Code (repository root)

```text
akka-pulse/
├── pom.xml                                  # aggregator only (packaging pom, no parent)
├── deploy/
│   ├── project-core.yaml                    # descriptor shape: main only
│   └── project-full.yaml                    # descriptor shape: main + peer
├── pulse-common/                            # library jar (no ServiceSetup, no application.conf)
│   ├── pom.xml                              # plain jar + SDK dep + annotation processor (R2)
│   └── src/
│       ├── main/java/com/example/common/
│       │   ├── api/HealthEndpoint.java              # GET /pulse/health (from PulseEndpoint)
│       │   ├── application/HealthCheckEntity.java   # moved
│       │   ├── application/SecretLoader.java        # moved
│       │   └── domain/
│       │       ├── HealthCheckEntry.java            # moved
│       │       └── PulseStreamEvent.java            # NEW public s2s stream event type
│       ├── main/resources/reference.conf            # library defaults (pulse.health.*)
│       └── test/java/com/example/common/application/
│           ├── HealthCheckEntityTest.java           # moved
│           └── SecretLoaderTest.java                # moved
├── pulse-core/                              # service "pulse-core" (was akka-pulse)
│   ├── pom.xml                              # parent akka-javasdk-parent, dep pulse-common
│   └── src/                                 # current src/ minus moved classes, plus:
│       ├── main/java/com/example/api/InternalPingEndpoint.java        # NEW, ACL service=pulse-peer
│       ├── main/java/com/example/application/SyntheticRecordStreamProducer.java  # NEW
│       └── test/java/...                    # existing tests + stream capture + health-via-library
└── pulse-peer/                              # service "pulse-peer", on-demand
    ├── pom.xml                              # parent akka-javasdk-parent, dep pulse-common
    └── src/
        ├── main/java/com/example/peer/
        │   ├── api/PeerProbeEndpoint.java           # /peer/probes/{direct,stream,restricted}
        │   ├── application/StreamProbeConsumer.java # @Consume.FromServiceStream(pulse-core)
        │   └── application/StreamCounterEntity.java # KVE counting consumed events
        ├── main/resources/application.conf          # pulse.health.service-name = pulse-peer
        └── test/java/com/example/peer/...           # stream mock IT, probe failure IT
```

**Structure Decision**: Maven multi-module with aggregator root; service modules keep
`akka-javasdk-parent` (Maven parent ≠ aggregator), library is a plain jar with the SDK annotation
processor (research R1/R2). Packages: library `com.example.common.*`, peer `com.example.peer.*`,
core unchanged `com.example.*` (R9).

## Implementation Phasing (for /akka:tasks)

1. **Discovery spike (gates everything)**: aggregator pom + pulse-common with only
   `HealthCheckEntity`/`HealthEndpoint` moved + pulse-core depending on it; integration test proves
   `/pulse/health` is served by the library-provided endpoint. If this fails, stop and revisit.
2. **Complete the library**: SecretLoader + `PulseStreamEvent`; move library tests; core imports updated.
3. **Core s2s surface**: `SyntheticRecordStreamProducer`, `InternalPingEndpoint`, tests.
4. **Peer service**: module, probes, consumer, counter, config, tests.
5. **Descriptors + docs**: `deploy/*.yaml` (schema from `akka project export`), README/docs updates
   for the new build/run/deploy commands.

## Key Decisions (details in research.md)

| Topic | Decision |
|-------|----------|
| Discovery from jar | Verified: `ComponentLocator` merges all `META-INF/akka-javasdk-components_*.conf`; library must not define a ServiceSetup (R1) |
| Library build | Plain jar + SDK annotation processor; NOT akka-javasdk-parent (R2) |
| Library config | `reference.conf` only; services set `pulse.health.service-name` in their `application.conf` (R3) |
| Health path | Unchanged `/pulse/health`, own `HealthEndpoint` class in library; response identical except configurable serviceName (R4) |
| Stream | Producer in core (`@Produce.ServiceStream(id = "synthetic-records")`, service ACL `*`), public event type in library, consumer in peer (R5) |
| Restricted endpoint | `/pulse/internal/ping` on core, `@Acl(service = "pulse-peer")` (R6) |
| Probes | 3 GET endpoints on peer, structured pass/fail JSON, never 5xx on probe failure (R7) |
| Naming | artifactId = image = service name: `pulse-core`, `pulse-peer` (rename from `akka-pulse`) (R8) |
| Descriptors | `deploy/project-core.yaml`, `deploy/project-full.yaml`, versioned with the repo (R8) |

## Complexity Tracking

No constitution violations to justify. The only deviation (library pom not using
`akka-javasdk-parent`) is documented in Constitution Check I and research R2.
