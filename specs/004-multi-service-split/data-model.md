# Data Model: Multi-Service Project Split

**Feature**: 004-multi-service-split | **Date**: 2026-09-07

Most domain records are relocated unchanged. New model elements are the public stream event type
and the peer probe results.

## pulse-common (shared library)

### HealthCheckEntry (moved, unchanged)

`com.example.common.domain.HealthCheckEntry`

| Field | Type | Notes |
|-------|------|-------|
| timestamp | Instant | when the probe wrote |
| status | String | "OK" |
| region | String | `commandContext().selfRegion()` at write time |

State of `HealthCheckEntity` (KVE, component id `health-check`, fixed entity id `heartbeat`).
Commands: `set()` (writes entry with current region), `get()` (errors if never written).

### PulseStreamEvent (new)

`com.example.common.domain.PulseStreamEvent` — public cross-service event type, sealed interface.
Produced by pulse-core's stream producer, consumed by pulse-peer. Mirrors the internal
`SyntheticRecordEvent` but is a deliberately separate public contract (internal events can evolve
without breaking consumers).

| Variant | @TypeName | Fields |
|---------|-----------|--------|
| RecordCreated | `pulse-record-created` | name: String, value: String, createdAt: Instant |
| RecordUpdated | `pulse-record-updated` | value: String, version: int, updatedAt: Instant |

Validation: none (pure data transfer). No state transitions.

### Health responses (moved from PulseEndpoint to HealthEndpoint, shape unchanged)

- `HealthUpResponse(status="UP", serviceName, version, region, timestamp, persistenceCheck: PersistenceCheckResult(status, latencyMs))`
- `HealthDownResponse(status="DOWN", serviceName, version, region="unknown", timestamp, persistenceCheck: PersistenceCheckError(status, error))`

`serviceName` now sourced from config `pulse.health.service-name` (per service);
`version` from `pulse.health.version` (reference.conf default `1.0-SNAPSHOT`).

## pulse-core (unchanged relocations + new)

All existing domain records (`SyntheticRecord`, `SyntheticRecordEvent`, `SyntheticEntry`,
`SyntheticWorkflowState`, `ConsumerCounter`, `SyntheticTopicMessage`, `TopicMessageCounter`) stay
in `com.example.domain`, unchanged.

### SyntheticRecordStreamProducer (new, Consumer)

Consumes `SyntheticRecordEvent` from `SyntheticRecordEntity`; produces `PulseStreamEvent` to
service stream `synthetic-records` with `@Acl(allow = @Acl.Matcher(service = "*"))`.
Mapping: RecordCreated → PulseStreamEvent.RecordCreated; RecordUpdated → PulseStreamEvent.RecordUpdated.

### InternalPingEndpoint (new, HTTP endpoint)

`GET /pulse/internal/ping` restricted with `@Acl(allow = @Acl.Matcher(service = "pulse-peer"))`.
Response: `PingResponse(serviceName: String, region: String, timestamp: Instant)`.

## pulse-peer

### StreamCounter (new)

`com.example.peer.domain.StreamCounter`

| Field | Type | Notes |
|-------|------|-------|
| count | long | events consumed from the `synthetic-records` stream |
| lastEventAt | Instant | timestamp of last consumed event (nullable → Optional in accessors) |

State of `StreamCounterEntity` (KVE, component id `stream-counter`, fixed entity id
`synthetic-records`). Command: `increment()`, `get()`.

### Probe results (new, API records in PeerProbeEndpoint)

| Record | Fields |
|--------|--------|
| DirectProbeResult | probe="direct", passed: boolean, target: String, evidence: String (core health status/region or error) |
| StreamProbeResult | probe="stream", passed: boolean (count > 0), consumedCount: long, lastEventAt: Instant? |
| RestrictedProbeResult | probe="restricted", passed: boolean, target: String, evidence: String (ping response or access error) |

Rule (R7): probe failures return `passed=false` with the error in `evidence`, HTTP 200 — never 5xx.

## Relationships

```text
pulse-core SyntheticRecordEntity --events--> SyntheticRecordStreamProducer
    --PulseStreamEvent (stream "synthetic-records")--> pulse-peer StreamProbeConsumer
    --increment--> StreamCounterEntity --get--> PeerProbeEndpoint /peer/probes/stream

PeerProbeEndpoint /peer/probes/direct     --HTTP s2s--> pulse-core /pulse/health
PeerProbeEndpoint /peer/probes/restricted --HTTP s2s--> pulse-core /pulse/internal/ping (ACL: pulse-peer only)

pulse-common HealthEndpoint + HealthCheckEntity --> discovered by BOTH services from the jar
```
