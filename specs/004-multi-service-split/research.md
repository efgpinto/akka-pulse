# Research: Multi-Service Project Split

**Feature**: 004-multi-service-split | **Date**: 2026-09-07

## R1. Component discovery from a dependency jar (primary risk)

**Decision**: Package shared components (endpoint + entity) in a library jar compiled with the
Akka SDK annotation processor. Consuming services discover them automatically.

**Rationale**: Verified against SDK 3.6.3 binaries and this project's build output:

- The annotation processor (`ComponentAnnotationProcessor`) writes a per-artifact descriptor
  `META-INF/akka-javasdk-components_<groupId>_<artifactId>.conf` (this project currently produces
  `akka-javasdk-components_com.example_akka-pulse.conf`). The groupId/artifactId come from the
  compiler args `-Aakka.javasdk.groupId` / `-Aakka.javasdk.artifactId` set by `akka-javasdk-parent`.
- `akka.javasdk.impl.ComponentLocator` scans the classpath for **all** files matching that pattern,
  including inside jars ("Found descriptor file in JAR"), and merges them ("Merging [{}] descriptor
  config(s)").
- Constraint: "Only one descriptor file should define a service-setup" — so the library MUST NOT
  contain a `ServiceSetup`/Bootstrap class; only the service module may.

**Alternatives considered**: Source-level sharing (build-helper adding the library's sources to each
service) — rejected: does not test the platform reuse feature, which is the point.
Copy-paste of health code per service — rejected: duplicates code, fails FR-002/SC-002.

**Validation step**: First implementation task builds pulse-common + a service depending on it and
asserts the health endpoint responds, before any further migration.

## R2. Maven module layout

**Decision**: Root `pom.xml` is a pure aggregator (packaging `pom`, no parent, modules list).
Each service module (`pulse-core`, `pulse-peer`) uses `io.akka:akka-javasdk-parent:3.6.3` as parent
(Maven allows parent ≠ aggregator). `pulse-common` is a plain jar module with:

- dependency on `io.akka:akka-javasdk` (the parent's BOM is not inherited, so version is set explicitly)
- `maven-compiler-plugin` configured exactly like the parent: `-parameters`,
  `-Aakka.javasdk.groupId=${project.groupId}`, `-Aakka.javasdk.artifactId=${project.artifactId}`,
  annotation processors `ComponentValidationProcessor` + `ComponentAnnotationProcessor`, processor
  path `io.akka:akka-javasdk-annotation-processor:3.6.3`.

**Rationale**: `akka-javasdk-parent` binds docker image build, exec (`kalix.runtime.AkkaRuntimeMain`),
and dev-mode wiring — all wrong for a library. A plain jar with the processor is sufficient for
descriptor generation (R1). Service modules keep the full parent so images and dev-mode keep working.

**Alternatives considered**: pulse-common also under `akka-javasdk-parent` — rejected: it would
attempt docker image builds for a non-deployable artifact (violates FR-010).

## R3. Library configuration

**Decision**: pulse-common ships `reference.conf` (library defaults: `pulse.health.version`);
each service keeps its own `application.conf` and sets `pulse.health.service-name`.

**Rationale**: Typesafe Config merges every `reference.conf` on the classpath but only loads one
`application.conf`. A library must never ship `application.conf` or it can shadow the service's own.

## R4. Reusable health endpoint shape

**Decision**: Move `HealthCheckEntity` (+ `HealthCheckEntry` domain record) and the health handler
into pulse-common as `HealthEndpoint` on path `/pulse/health` (own endpoint class; `PulseEndpoint`
loses its health block). Response shape unchanged from today, except `serviceName` comes from config
(`pulse.health.service-name`) so each service identifies itself (edge case: same path, two services).

**Rationale**: Keeps SC-001 (existing path unchanged on core) while making the same endpoint appear
in pulse-peer automatically via R1. Component id `health-check` is unchanged; ids are per-service so
both services having it is fine.

## R5. Service-to-service eventing (stream probe)

**Decision**:
- Core adds `SyntheticRecordStreamProducer`: `@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)`
  + `@Produce.ServiceStream(id = "synthetic-records")` + `@Acl(allow = @Acl.Matcher(service = "*"))`,
  transforming internal `SyntheticRecordEvent` into a public event type.
- The public event type (`PulseStreamEvent` sealed interface, `@TypeName`d records) lives in
  pulse-common domain so producer and consumer share one definition (second reuse of the library).
- Peer adds `StreamProbeConsumer`: `@Consume.FromServiceStream(service = "pulse-core",
  id = "synthetic-records")`, incrementing a peer-local counter entity that the probe endpoint reads.

**Rationale**: Matches the documented pattern in `akka-context/sdk/consuming-producing.html.md`
(producer transforms internal events to public types; consumer subscribes by service name + stream id).
Brokerless, so the peer works in projects without a broker (unlike the topic components).

**Testing**: TestKit supports mocking an upstream service stream on the consumer side and capturing
an outgoing service stream on the producer side (both documented in consuming-producing.html.md).

## R6. S2S HTTP call + ACL-restricted endpoint

**Decision**:
- Direct-call probe: peer injects `akka.javasdk.http.HttpClientProvider`, calls
  `httpClientFor("pulse-core")` → `GET /pulse/health`, and reports the result with evidence.
- Restricted endpoint: core adds `InternalPingEndpoint` (`/pulse/internal/ping`) with
  `@Acl(allow = @Acl.Matcher(service = "pulse-peer"))`; peer probe calls it s2s.
  Internet callers are denied by the ACL.

**Rationale**: `HttpClientProvider.httpClientFor(<service-name>)` is the documented s2s mechanism
(`component-and-service-calls.html.md`); service-principal ACL matcher is the documented restriction
mechanism. ACL enforcement is a platform behavior — locally probes are validated for wiring, on the
platform for enforcement (noted in quickstart).

## R7. Peer probe endpoint behavior

**Decision**: Peer exposes three GET probes returning explicit pass/fail JSON with evidence:
`/peer/probes/direct`, `/peer/probes/stream`, `/peer/probes/restricted`. A failed call (core
unreachable, access denied) returns a structured FAIL result with the error, never a 5xx crash.

**Rationale**: SC-003 (≤3 requests, explicit pass/fail each) and the edge case "peer deployed before
core must report clear failures and recover".

## R8. Service naming and project descriptor

**Decision**: Service names = artifact ids = image names: `pulse-core`, `pulse-peer` (rename from
`akka-pulse`). Repo maps one-to-one to an Akka project. Two checked-in descriptor shapes under
`deploy/`: `project-core.yaml` (main only) and `project-full.yaml` (main + peer), image tags left as
documented placeholders updated at deploy time. Exact schema taken from `akka project export` of the
current project at implementation time.

**Rationale**: Stream consumer (`service = "pulse-core"`) and ACL matcher (`service = "pulse-peer"`)
hardcode service names; aligning artifactId, image, and service name keeps one name everywhere.
The parent pom already defaults `docker.image` to `${project.artifactId}`.

**Alternatives considered**: keep deployed name `akka-pulse` for the core — rejected: two names for
one thing across descriptor, ACLs, and stream subscriptions invites drift; this is a test project so
the rename is cheap.

## R9. Package structure

**Decision**: pulse-common uses `com.example.common.{api,application,domain}`; pulse-peer uses
`com.example.peer.{api,application,domain}`; pulse-core keeps `com.example.{api,application,domain}`
unchanged (only imports of moved classes change).

**Rationale**: Follows the `[org].[module].[api|application|domain]` convention while minimizing
diff churn in the existing service.

## R10. Test relocation

**Decision**:
- pulse-common: `HealthCheckEntityTest` (KVE testkit, pure unit) and `SecretLoaderTest` move with
  their classes.
- pulse-core: all remaining tests stay; add producer-side stream capture test; add an integration
  test asserting `/pulse/health` still serves (library-provided endpoint — the R1 validation).
- pulse-peer: integration test mocking the upstream stream and asserting the stream probe counter;
  test that direct/restricted probes return structured FAIL when core is unreachable.

**Rationale**: FR-011 (tests move with the code they test), constitution III.
