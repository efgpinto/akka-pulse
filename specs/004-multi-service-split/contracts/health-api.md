# Contract: Shared Health API (pulse-common)

Served by every service that depends on pulse-common. Path and response shape are identical to the
pre-split `/pulse/health`, except `serviceName` identifies the serving service.

## GET /pulse/health

ACL: internet-allowed.

**200 OK** — persistence round trip succeeded:

```json
{
  "status": "UP",
  "serviceName": "pulse-core",
  "version": "1.0-SNAPSHOT",
  "region": "<self-region>",
  "timestamp": "2026-09-07T12:00:00Z",
  "persistenceCheck": { "status": "OK", "latencyMs": 12 }
}
```

**503 Service Unavailable** — write/read probe failed:

```json
{
  "status": "DOWN",
  "serviceName": "pulse-core",
  "version": "1.0-SNAPSHOT",
  "region": "unknown",
  "timestamp": "2026-09-07T12:00:00Z",
  "persistenceCheck": { "status": "FAILED", "error": "Persistence round-trip failed: ..." }
}
```

`serviceName` = config `pulse.health.service-name` (`pulse-core` / `pulse-peer`);
`version` = config `pulse.health.version` (default `1.0-SNAPSHOT` from reference.conf).
