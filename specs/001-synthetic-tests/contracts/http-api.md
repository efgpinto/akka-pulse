# HTTP API Contract: Synthetic Test Suite

**Date**: 2026-06-07

## Base Path: `/pulse`

---

### Health Endpoint

#### `GET /pulse/health`

Returns service health status including persistence round-trip verification.

**Response** (200 OK):
```json
{
  "status": "UP",
  "serviceName": "akka-pulse",
  "version": "1.0-SNAPSHOT",
  "timestamp": "2026-06-07T12:00:00Z",
  "persistenceCheck": {
    "status": "OK",
    "latencyMs": 12
  }
}
```

**Response** (503 Service Unavailable):
```json
{
  "status": "DOWN",
  "serviceName": "akka-pulse",
  "version": "1.0-SNAPSHOT",
  "timestamp": "2026-06-07T12:00:00Z",
  "persistenceCheck": {
    "status": "FAILED",
    "error": "Persistence round-trip failed: connection refused"
  }
}
```

---

### Event Sourced Entity Endpoints

#### `POST /pulse/ese/{recordId}/create`

**Request**:
```json
{
  "name": "test-record",
  "value": "initial-value",
  "delaySeconds": 0
}
```

**Response** (201 Created): `SyntheticRecord` state

#### `POST /pulse/ese/{recordId}/update`

**Request**:
```json
{
  "value": "updated-value",
  "delaySeconds": 0
}
```

**Response** (200 OK): `SyntheticRecord` state

#### `GET /pulse/ese/{recordId}`

**Response** (200 OK): `SyntheticRecord` state

---

### Key Value Entity Endpoints

#### `POST /pulse/kve/{entryId}`

**Request**:
```json
{
  "data": "some-payload",
  "delaySeconds": 0
}
```

**Response** (200 OK): `SyntheticEntry` state

#### `GET /pulse/kve/{entryId}`

**Response** (200 OK): `SyntheticEntry` state

#### `DELETE /pulse/kve/{entryId}`

**Response** (200 OK): confirmation

---

### View Endpoints

#### `GET /pulse/view/by-name/{name}`

**Response** (200 OK):
```json
{
  "entries": [
    {
      "recordId": "abc-123",
      "name": "test-record",
      "value": "some-value",
      "status": "UPDATED"
    }
  ]
}
```

#### `GET /pulse/view/all`

**Response** (200 OK): Same structure as above

---

### Workflow Endpoints

#### `POST /pulse/workflows/{workflowId}/start`

**Request**:
```json
{
  "input": "test-data",
  "mode": "normal",
  "delaySeconds": 0
}
```

`mode` values: `"normal"` (happy path), `"trigger-failure"` (forces compensation)

**Response** (201 Created): confirmation with workflow ID

#### `GET /pulse/workflows/{workflowId}`

**Response** (200 OK): `WorkflowState`

---

### Consumer Counter Endpoints

#### `GET /pulse/consumers/{counterId}`

**Response** (200 OK):
```json
{
  "counterId": "synthetic-record-counter",
  "eventCount": 42,
  "lastEventType": "record-updated",
  "lastEventAt": "2026-06-07T12:00:00Z"
}
```

---

### Timed Action Endpoints

#### `POST /pulse/timers/{timerId}/schedule`

**Request**:
```json
{
  "delaySeconds": 5
}
```

**Response** (200 OK): confirmation

#### `GET /pulse/timers/{timerId}`

**Response** (200 OK): last execution info

---

### Burst Endpoint

#### `POST /pulse/burst`

**Request**:
```json
{
  "target": "ese",
  "count": 50,
  "delaySeconds": 0
}
```

`target` values: `"ese"`, `"kve"`

**Constraints**: `count` max 100, `delaySeconds` max 300

**Response** (200 OK):
```json
{
  "target": "ese",
  "requested": 50,
  "succeeded": 50,
  "failed": 0,
  "totalDurationMs": 234
}
```

---

### OpenAPI Spec

#### `GET /pulse/openapi.yaml`

**Response** (200 OK): OpenAPI 3.x YAML document

Content-Type: `application/yaml`
