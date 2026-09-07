# Contract: pulse-peer probe API

ACL: internet-allowed. All probes return **200 OK** with `passed` true/false; a failing probe never
returns 5xx (structured failure with evidence instead).

## GET /peer/probes/direct

Calls `GET /pulse/health` on pulse-core via s2s HTTP.

```json
{ "probe": "direct", "passed": true, "target": "pulse-core", "evidence": "UP region=<region>" }
```

Failure: `{ "probe": "direct", "passed": false, "target": "pulse-core", "evidence": "<error>" }`

## GET /peer/probes/stream

Reports events consumed from pulse-core's `synthetic-records` service stream.

```json
{ "probe": "stream", "passed": true, "consumedCount": 5, "lastEventAt": "2026-09-07T12:00:00Z" }
```

`passed` is true when `consumedCount > 0`. Zero consumed (stream never flowed) →
`{ "probe": "stream", "passed": false, "consumedCount": 0, "lastEventAt": null }`.

## GET /peer/probes/restricted

Calls `GET /pulse/internal/ping` on pulse-core (endpoint only pulse-peer may call).

```json
{ "probe": "restricted", "passed": true, "target": "pulse-core", "evidence": "pong from pulse-core region=<region>" }
```

Failure (denied or unreachable): `{ "probe": "restricted", "passed": false, "target": "pulse-core", "evidence": "<error>" }`

## GET /pulse/health

Provided by pulse-common (see health-api.md), `serviceName` = `pulse-peer`.
