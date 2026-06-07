# Research: Synthetic Test Suite & Health Monitoring

**Date**: 2026-06-07

## OpenAPI Integration

**Decision**: Use the Akka OpenAPI Maven plugin (`sh.oso:akka-openapi-maven-plugin`) to auto-generate OpenAPI specs from HTTP endpoint annotations.

**Rationale**: This is the documented, purpose-built plugin for Akka SDK HTTP endpoints. It scans `@HttpEndpoint` annotated classes and generates an OpenAPI 3.x spec automatically.

**Alternatives considered**:
- `springdoc-openapi` — not compatible with Akka SDK (Spring-specific)
- `swagger-core` annotations — requires manual annotation of every endpoint; the Akka plugin handles this automatically
- Hand-written OpenAPI spec — maintenance burden, falls out of sync

**Implementation**:
- Add plugin to `pom.xml` with output directed to `target/classes/static-resources/openapi.yaml`
- Serve via an endpoint using `HttpResponses.staticResource("openapi.yaml")`

## Configurable Delay Mechanism

**Decision**: Use `Thread.sleep()` in entity command handlers and workflow steps to simulate processing delay.

**Rationale**: Simplest approach that genuinely blocks the processing thread, which is what's needed to trigger latency-based alerting. The delay is experienced by the caller as actual response latency.

**Alternatives considered**:
- `CompletionStage.delayed()` — Akka SDK endpoints favor synchronous style; async delays would complicate the code
- Timer-based delays — over-engineered for a synthetic test tool

**Constraints**: Maximum 300 seconds, validated before processing.

## Burst Request Implementation

**Decision**: Use `CompletableFuture.allOf()` in the burst endpoint to fire parallel requests via `ComponentClient`.

**Rationale**: Fires all requests concurrently from the endpoint, creating a genuine traffic spike. The endpoint collects results and returns a summary.

**Alternatives considered**:
- Sequential loop — doesn't create a spike pattern in metrics
- External load tool — defeats the purpose of a self-contained synthetic test service

**Constraints**: Maximum 100 concurrent requests per burst.

## Health Check Persistence Round-Trip

**Decision**: Use a dedicated Key Value Entity (`HealthCheckEntity`) with a fixed entity ID (`"heartbeat"`) for the write/read probe.

**Rationale**: KV Entity is simpler than ES Entity for a write/overwrite/read pattern. A fixed ID avoids unbounded data growth. The health check writes a timestamp and reads it back to confirm persistence is working.

**Alternatives considered**:
- Reuse the synthetic KV Entity — couples health check to test data; a dedicated entity keeps concerns separate
- Event Sourced Entity — unnecessarily complex for a heartbeat; would accumulate events over time

## Consumer Observability

**Decision**: Consumer increments a counter in a dedicated Key Value Entity (`ConsumerCounterEntity`) for each event processed.

**Rationale**: Makes consumer activity queryable through an endpoint. The counter entity tracks total event count, last event type, and last event timestamp.

**Alternatives considered**:
- Logging only — not queryable through endpoints; can't verify programmatically
- View projection — would require the consumer to write to an entity anyway; a simple counter is more direct

## Workflow Design

**Decision**: Two-step validate-then-persist workflow with three command types: normal (succeeds), trigger-failure (forces compensation), and delayed (adds configurable step delay).

**Rationale**: Covers the core workflow scenarios (happy path, failure/compensation, slow processing) with minimal complexity. The workflow writes to the synthetic KV Entity in step 2, which ties it into the broader component graph.

**Steps**:
1. **Validate step**: Checks input, rejects invalid data. For "trigger-failure" commands, passes through to step 2 which will fail.
2. **Persist step**: Writes to KV Entity. For "trigger-failure" commands, this step is configured to fail, triggering the compensation step.
3. **Compensation step** (recovery only): Logs the failure and marks workflow as compensated.
