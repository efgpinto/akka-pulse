# Local validation run (T025)

**Date**: 2026-09-07 | **Command**: `cd pulse-core && mvn compile exec:java`

| Check | Result |
|-------|--------|
| `GET /pulse/health` | `{"status":"UP","serviceName":"pulse-core","version":"1.0-SNAPSHOT",...,"persistenceCheck":{"status":"OK","latencyMs":5}}` — served by pulse-common's HealthEndpoint (SC-001, SC-002) |
| `POST /pulse/ese/smoke-1/create` | `{"recordId":"smoke-1","name":"smoke","value":"v1","status":"CREATED","version":1,...}` — pre-split probe unchanged (SC-001) |
| `GET /pulse/internal/ping` (no identity) | `403` — ACL denies unidentified callers (FR-007, SC-006) |
| `GET /pulse/internal/ping` with `impersonate-service: pulse-peer` | `{"serviceName":"pulse-core","region":"",...}` — allowed for the peer principal |

Gotcha found and documented in README: local `mvn compile exec:java` resolves pulse-common from
the **local Maven repository**, not the reactor. After changing pulse-common, run
`mvn install -pl pulse-common` (or a root `mvn install`) first, or the service starts against a
stale library jar (observed as `NoClassDefFoundError: com/example/common/application/SecretLoader`).
