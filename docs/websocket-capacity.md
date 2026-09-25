# WebSocket connection capacity

How many WebSocket connections can one pulse-core instance hold in parallel, how that was
measured, and what limits it.

## Answer

| Environment | Parallel connections per instance | Limiting factor |
|-------------|-----------------------------------|-----------------|
| Local dev (`mvn exec:java`, Akka runtime 1.6.16) | **4096**, exact | `akka.http.server.max-connections = 4096` in the runtime's `runtime-common.conf` |
| Dev platform (`eduardo-playground-dev`, `g1.general.x-small`, 1 instance) | _filled in below_ | _filled in below_ |

The cap is per runtime instance. Scaling out (`autoscaling.minInstances`) multiplies it, as
long as the route balances new connections across instances. The runtime config comment states
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

_filled in below_

## Dev platform results

_filled in below_

## Caveats

- The load generator ran on a laptop against the public route. Handshake and RTT numbers
  include TLS, the internet path, and the platform ingress. Treat them as relative.
- macOS gives a client about 16k ephemeral ports; runs above ~15k connections from one client
  need a second client host.
- The runtime sends WebSocket keep-alive pings every 22 s
  (`websocket.periodic-keep-alive-max-idle`) to survive proxy idle timeouts. It does not reap
  dead clients on its own; the server-side `idle-timeout` is 12 h.
