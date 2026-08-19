# HTTP API Additions: Multi-Region Test Scenarios

**Date**: 2026-08-05
**Base Path**: `/pulse`

Only the additions and changes for this feature are listed. Existing endpoints are
in `specs/001-synthetic-tests/contracts/http-api.md` and stay unchanged (FR-015/FR-016).

---

## Changed: `GET /pulse/health`

The health response gains a `region` field (FR-014). The region is read inside
`HealthCheckEntity` via `commandContext().selfRegion()` during the write/read
round-trip, then surfaced by the endpoint.

**Response** (200 OK):
```json
{
  "status": "UP",
  "serviceName": "akka-pulse",
  "version": "1.0-SNAPSHOT",
  "region": "gcp-us-east1",
  "timestamp": "2026-08-05T12:00:00Z",
  "persistenceCheck": {
    "status": "OK",
    "latencyMs": 12
  }
}
```

**Response** (503 Service Unavailable): unchanged shape, plus `region` when available.

**Note**: Replication lag/status is **not** in this response. It is observed in the
Control Tower replication section (see `quickstart.md`). The health endpoint reports
region identity only.

---

## New: `GET /pulse/topic-counter/{counterId}`

Returns the topic message counter, so duplicate publishing is observable (FR-010, SC-009).

**Response** (200 OK):
```json
{
  "counterId": "synthetic-record-events",
  "messageCount": 5,
  "lastOriginRegion": "gcp-us-east1",
  "lastMessageAt": "2026-08-05T12:00:00Z"
}
```

Interpretation:
- `origin-only` mode: `messageCount` equals the number of record events (one per event).
- `every-region` mode: `messageCount` equals events × number of regions.
