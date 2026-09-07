# Quickstart: Multi-Service Project Split

## Build everything

```shell
mvn verify          # from repo root: builds pulse-common, pulse-core, pulse-peer, runs all tests
```

Before running a service with `exec:java`, install the library to the local repo
(`mvn install -DskipTests` from the root, or `mvn install -pl pulse-common` after library
changes) — the exec plugin resolves pulse-common from the local repository, not the reactor.

## Run pulse-core locally

```shell
cd pulse-core
mvn compile exec:java
curl http://localhost:9000/pulse/health          # served by pulse-common's HealthEndpoint
```

All pre-split probes work unchanged (see root README for the full list).

## Run both services locally (s2s validation)

Terminal 1 (core, default port 9000):

```shell
cd pulse-core && mvn compile exec:java
```

Terminal 2 (peer, different port):

```shell
cd pulse-peer && mvn compile exec:java -Dakka.javasdk.dev-mode.http-port=9001
```

Local dev-mode discovers sibling services by their dev-mode service names
(see akka-context "Running multiple services"). Then:

```shell
curl http://localhost:9001/pulse/health           # peer's own health (library reuse)
curl http://localhost:9001/peer/probes/direct     # s2s HTTP call to core
curl http://localhost:9001/peer/probes/stream     # events consumed from core's stream
                                                  # (create a record on core first:
                                                  #  curl -X POST localhost:9000/pulse/ese/t1/create -H 'Content-Type: application/json' \
                                                  #       -d '{"name":"n","value":"v","delaySeconds":0}')
curl http://localhost:9001/peer/probes/restricted # s2s call to core's ACL-restricted endpoint
```

Note: ACL *enforcement* (denying internet on `/pulse/internal/ping`) is platform behavior;
locally the probes validate wiring only.

## Deploy from the descriptor

Main-only shape:

```shell
mvn clean install -DskipTests                     # builds pulse-core + pulse-peer images
akka project apply -f deploy/project-core.yaml    # update image tags first
```

Main + peer (s2s validation window):

```shell
akka project apply -f deploy/project-full.yaml
```

Validate on the platform:

```shell
curl https://<core-route>/pulse/health                       # UP, serviceName=pulse-core
curl https://<peer-route>/peer/probes/direct                 # passed=true
curl https://<peer-route>/peer/probes/stream                 # passed=true after creating a record
curl https://<peer-route>/peer/probes/restricted             # passed=true
curl https://<core-route>/pulse/internal/ping                # DENIED (403) from internet
```

Tear down the peer when done; pulse-core is unaffected (re-apply `project-core.yaml` or
`akka service undeploy pulse-peer`).
