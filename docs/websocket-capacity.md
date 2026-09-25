# WebSocket connection capacity

How many WebSocket connections can one pulse-core instance hold in parallel, how that was
measured, and what limits it.

## Answer

| Environment | Parallel connections per instance | Limiting factor |
|-------------|-----------------------------------|-----------------|
| Local dev (`mvn exec:java`, Akka runtime 1.6.16) | **4096**, exact | `akka.http.server.max-connections = 4096` in the runtime's `runtime-common.conf` |
| Dev platform (`eduardo-playground-dev`, `g1.general.x-small`, 1 instance, runtime 1.6.17, via the public route) | **4096**, exact | same runtime setting; the ingress passes WebSockets through as HTTP/1.1 connections one to one |

The cap is per runtime instance. Scaling out multiplies it: on dev the autoscaler added a
second instance under load and the service then held 8191 connections across the two. The runtime config comment states
the value is meant for self-hosted and dev setups because on the platform the service mesh
sidecar normally multiplexes traffic over one HTTP/2 connection. WebSocket routes are the
exception: `enableWebSockets: true` downgrades the route to HTTP/1.1, so each WebSocket is a
separate connection into the runtime.

## Probes

Server side, in pulse-core (`WebSocketEndpoint`, `/pulse/ws`):

| Path | Kind | Purpose |
|------|------|---------|
| `WS /pulse/ws/echo` | client-driven | echoes every text message; used to hold connections and measure round-trip latency |
| `WS /pulse/ws/ticker/{intervalSeconds}` | server push | one JSON tick per interval (1..60 s) with `seq`, `instanceId`, `openConnections` |
| `GET /pulse/ws/stats` | HTTP | per-instance counters: open, peak, totals, messages in/out |
| `POST /pulse/ws/stats/reset` | HTTP | clears peak and totals before a run |

Counters live in memory in `WebSocketConnectionTracker` (not an Akka component). A WebSocket
is pinned to the instance that accepted it, so per-instance counters are the honest measure.
`instanceId` is the hostname (the pod name when deployed).

Client side: `tools/ws-load/WsLoad.java`, a JDK 21 single-file program built on
`java.net.http.WebSocket`. It opens N connections with a bounded number of handshakes in
flight, holds them, pings every open connection on an interval, then closes them all and
reports open failures by cause, handshake latency, echo latency and connections the server
closed on its own.

```shell
java tools/ws-load/WsLoad.java --url ws://localhost:9000/pulse/ws/echo \
  --connections 4300 --concurrency 100 --hold 10 --ping-interval 5 --timeout 10
```

## Method

1. Ramp: 200, 2000, 10000 connections to find where opens start failing.
2. Verify slots are released: 3000 open and close, then 3000 again.
3. Bracket the cap: 4300 attempts against a suspected limit of 4096.
4. Check recovery: after every run, poll `/pulse/health` and the stats endpoint.

## Local results (macOS, client and server on the same host)

| Run | Opened | Failed | Open phase | Handshake p50 / p99 | Echo RTT p50 / p99 |
|-----|--------|--------|------------|---------------------|--------------------|
| 200 | 200 | 0 | 0.3 s | 62 / 137 ms | 12 / 22 ms |
| 2000 | 2000 | 0 | 1.3 s | 53 / 165 ms | 20 / 50 ms |
| 3000, twice in a row | 3000 + 3000 | 0 | 1.7 s, 1.2 s | | |
| 4300 (cap 4096) | **4096** | 204 | 31 s | 32 / 57 ms | 40 / 76 ms |

Observations:

- At the cap the runtime stops accepting. New handshakes sit in `SYN_SENT` and fail on the
  client's timeout (`HTTP connect timed out` once the kernel backlog is full, `request timed
  out` for the ones the kernel accepted but the runtime never picked up).
- While the cap is reached, **every** HTTP request to that instance hangs, including
  `/pulse/health`, because the limit is on connections, not on WebSockets.
- Closed connections release their slot: two back-to-back runs of 3000 both succeed.
- After the 4300 run closed its connections, health and stats answered again within 5 s.
- One run did not recover: a 10000-attempt run whose client kept retrying against the full
  server for several minutes and was then killed. The runtime ended up with zero open
  connections, idle threads, and an HTTP server that never answered again until restart.
  See "Recovery after overload" below for the reproduction attempt.
- Memory: the server JVM used about 460 MB RSS before the 10000 run (dev-mode runtime, no
  tuning); per-connection cost was not isolated.

## Recovery after overload

Local reproduction, dev-mode runtime 1.6.16, one server on the laptop:

| Run | Outcome |
|-----|---------|
| 4300 attempts, 100 in flight, 10 s client timeout, 10 s hold | 4096 opened, 204 failed, server answered health 5 s after the close |
| 10000 attempts, 200 in flight, 5 s client timeout, 10 s hold (2.5 min of retries against a full server) | 4096 opened, 5904 failed, server **never answered again**: zero open connections, 3 TCP file descriptors, idle dispatcher threads, no blocked threads, accept loop without demand. Restart required. Reproduced twice. |

The runtime binds a plain Akka HTTP server (`Http().newServerAt(...).bind(route)` in
`DiscoveryManager`), so the cap is Akka HTTP's `max-connections` slot accounting and the stall
is a slot leak in that accounting under sustained accept-backlog overflow. Worth a runtime
issue; the thread dumps are in the session scratchpad.

On the dev platform the same sustained overload (10000 attempts, 200 in flight, 8 s timeout)
did not stall anything. The ingress fails overflow handshakes fast with `504` instead of
letting them sit in the kernel backlog, and the platform reacted to the saturated instance by
scaling out (see below), so the second wave of attempts landed on a fresh pod.


## Dev platform results

Service `pulse-core`, one instance of `g1.general.x-small`, reached through the public route
`quiet-rain-1815.aws-us-east-2.apps.akka.dev` with `enableWebSockets: true`. Client on a laptop
in Europe, server in `aws-us-east-2`, TLS on every connection.

| Run | Opened | Failed | Open phase | Handshake p50 / p99 | Echo RTT p50 / p99 |
|-----|--------|--------|------------|---------------------|--------------------|
| 200 | 200 | 0 | 2.1 s | 402 / 829 ms | 140 / 162 ms |
| 1000 | 1000 | 0 | 7.6 s | 347 / 611 ms | 225 / 829 ms |
| 2500 | 2500 | 0 | 10.4 s | 378 / 719 ms | 374 / 529 ms |
| 4300 (cap 4096) | **4096** | 204 | 31.7 s | 372 / 1341 ms | 566 / 756 ms |
| 4200, 40 s hold, health probed during the hold | **4096** | 104 | 25.5 s | 401 / 1265 ms | 532 / 681 ms |

Observations:

- Same cap as locally, 4096 per instance. No ingress-level limit was hit first: the 1024 and
  2048 marks passed without a single failure.
- Overflow connections fail in two ways: the ingress answers `504` once its upstream timeout
  expires, or the client's own handshake timeout fires first.
- While the instance holds 4096 WebSockets, every other HTTP request to it fails with `504`
  from the ingress: `/pulse/health` and `/pulse/ws/stats` both did, for the whole hold. The
  platform noticed too, logging `Pod ... Unhealthy: Unable to determine if your service is
  ready` for the instance. Readiness failing means the instance drops out of the route's
  endpoints for regular traffic while it is saturated with WebSockets.
- Both endpoints answered `200` again within 5 s after the connections closed. No pod restart.
- Echo RTT grows with the connection count because the load generator sends one ping burst to
  every open connection on each interval; at 4096 connections that burst is queued on the
  client side and on the ingress. Treat RTT as relative.
- Handshake rate through the route stayed between 130 and 240 connections per second with
  100 handshakes in flight, so filling one instance takes about 30 s.

Scale-out under WebSocket load:

- During the 4200-connection run the platform created a second instance
  (`ReplicaSet SuccessfulCreate` at 10:35:23 WEST, 12 s before the readiness warning for the
  saturated pod). The service has `autoscaling.minInstances: 1` and no explicit maximum.
- The next run, 10000 attempts, opened **8191** connections: 4096 on the original pod plus 4095
  on the new one, both visible through `/pulse/ws/stats` by `instanceId`. Handshake rate rose
  to 262 connections per second. The 1809 failures were overflow beyond both pods.
- So the practical answer for the deployed service is 4096 per instance, times the number of
  instances the autoscaler is allowed to run. The route must spread new WebSocket handshakes
  across instances for this to hold; it did here.

## Caveats

- The load generator ran on a laptop against the public route. Handshake and RTT numbers
  include TLS, the internet path, and the platform ingress. Treat them as relative.
- macOS gives a client about 16k ephemeral ports; runs above ~15k connections from one client
  need a second client host.
- The runtime sends WebSocket keep-alive pings every 22 s
  (`websocket.periodic-keep-alive-max-idle`) to survive proxy idle timeouts. It does not reap
  dead clients on its own; the server-side `idle-timeout` is 12 h.
