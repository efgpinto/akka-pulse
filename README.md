# Akka Pulse - Synthetic Test Suite & Health Monitoring

A synthetic test service built on [Akka SDK](https://doc.akka.io/sdk/index.html) that exercises every component type for infrastructure validation. Deploy to any environment and verify that all platform components are working correctly.

## Components

| Component | Type | Purpose |
|-----------|------|---------|
| SyntheticRecordEntity | Event Sourced Entity | Validate event journal persistence |
| SyntheticEntryEntity | Key Value Entity | Validate key-value state persistence |
| HealthCheckEntity | Key Value Entity | Deep health check write/read probe |
| ConsumerCounterEntity | Key Value Entity | Track consumer event processing |
| SyntheticRecordView | View | Validate read-side projections |
| SyntheticWorkflow | Workflow | Validate step transitions and compensation |
| SyntheticEventConsumer | Consumer | Validate event consumption pipeline |
| SyntheticTimedAction | Timed Action | Validate timer scheduling |

## Swagger UI

Once the service is running, open [http://localhost:9000/pulse/docs](http://localhost:9000/pulse/docs) in your browser for interactive API documentation.

## Build & Run

```shell
mvn compile exec:java
```

## Run Tests

```shell
mvn verify
```

## API Endpoints

### Health Check

```shell
curl http://localhost:9000/pulse/health
```

### Event Sourced Entity

```shell
# Create
curl -X POST http://localhost:9000/pulse/ese/test-1/create \
  -H "Content-Type: application/json" \
  -d '{"name":"my-record","value":"hello","delaySeconds":0}'

# Update
curl -X POST http://localhost:9000/pulse/ese/test-1/update \
  -H "Content-Type: application/json" \
  -d '{"value":"updated","delaySeconds":0}'

# Query
curl http://localhost:9000/pulse/ese/test-1
```

### Key Value Entity

```shell
# Set
curl -X POST http://localhost:9000/pulse/kve/entry-1 \
  -H "Content-Type: application/json" \
  -d '{"data":"my-payload","delaySeconds":0}'

# Get
curl http://localhost:9000/pulse/kve/entry-1

# Delete
curl -X DELETE http://localhost:9000/pulse/kve/entry-1
```

### View

```shell
curl http://localhost:9000/pulse/view/by-name/my-record
curl http://localhost:9000/pulse/view/all
```

### Workflow

```shell
# Normal flow
curl -X POST http://localhost:9000/pulse/workflows/wf-1/start \
  -H "Content-Type: application/json" \
  -d '{"input":"test","mode":"normal","delaySeconds":0}'

# Trigger failure/compensation
curl -X POST http://localhost:9000/pulse/workflows/wf-2/start \
  -H "Content-Type: application/json" \
  -d '{"input":"trigger-failure","mode":"trigger-failure","delaySeconds":0}'

# Check status
curl http://localhost:9000/pulse/workflows/wf-1
```

### Consumer Counter

```shell
curl http://localhost:9000/pulse/consumers/synthetic-record-counter
```

### Topic Message Counter

Tracks messages published to the `synthetic-record-events` topic by the topic producer.
Use it to observe cross-region publishing (one message per event in origin-only mode).

```shell
curl http://localhost:9000/pulse/topic-counter/synthetic-record-events
```

### Timed Action

```shell
curl -X POST http://localhost:9000/pulse/timers/timer-1/schedule \
  -H "Content-Type: application/json" \
  -d '{"delaySeconds":5}'
```

### Configurable Delay

```shell
# Slow entity command (3 seconds)
curl -X POST http://localhost:9000/pulse/ese/slow-1/create \
  -H "Content-Type: application/json" \
  -d '{"name":"slow","value":"test","delaySeconds":3}'
```

### Burst Traffic

```shell
# 50 parallel requests
curl -X POST http://localhost:9000/pulse/burst/ \
  -H "Content-Type: application/json" \
  -d '{"target":"ese","count":50,"delaySeconds":0}'
```

### JWT Endpoint (disabled by default)

Enable by setting the `JWT_ISSUER` environment variable:

```shell
JWT_ISSUER=my-issuer mvn compile exec:java
```

Then test with a valid Bearer token:

```shell
curl http://localhost:9000/pulse/jwt/test \
  -H "Authorization: Bearer <your-jwt-token>"
```

### External Secrets

Verifies that secrets injected by the platform reach the running service. It reads secrets
from environment variables and from volume-mounted files. This is a synthetic test probe. It
returns the full value so you can confirm the exact injected content. Do not point it at real
production secrets.

Two settings control the probe (defaults shown):

- `pulse.secrets.env-prefix` (`PULSE_`) — env override `PULSE_SECRETS_ENV_PREFIX`. The list
  probe reports environment variables with this prefix.
- `pulse.secrets.file-dir` (`/secrets/pulse-test-file`) — env override `PULSE_SECRETS_FILE_DIR`.
  The directory where file-mounted secrets are read.

```shell
# Read a secret from an environment variable
curl http://localhost:9000/pulse/secrets/env/PULSE_TEST_SECRET

# Read a secret from a mounted file
curl http://localhost:9000/pulse/secrets/file/db-password

# List all Pulse test secrets from both sources
curl http://localhost:9000/pulse/secrets/

# Load a secret: mounted file wins, else config (pulse.file-secrets.<name>)
curl http://localhost:9000/pulse/secrets/load/client-password
```

Each result reports a `status`: `PRESENT`, `EMPTY` (file exists but is empty), or `ERROR` (file
could not be read). A missing secret returns `404` and never fails the service.

#### Loading a secret in application code (`SecretLoader`)

`SecretLoader` is the pattern a real service uses to read a secret the same way locally and when
deployed:

```java
String pw = SecretLoader.load(config, "/secrets/pulse-test-file/client-password",
                              "pulse.file-secrets.client-password");
```

- **Deployed:** the mounted file wins — e.g. an external secret from Azure Key Vault.
- **Local:** no file, so it falls back to config. Seed that key from an env var:
  `pulse.file-secrets.client-password = ${?PULSE_CLIENT_PASSWORD}`.

The `/pulse/secrets/load/{name}` probe exercises exactly this and reports the winning `source`
(`file` or `config`).

Note: `.env` files are not auto-loaded by `mvn exec:java`. Load them yourself:

```shell
set -a; source .env; set +a
mvn compile exec:java
```

#### Wiring an Azure Key Vault external secret

External secrets (AKV) mount as **files only** and authenticate via workload identity (no stored
credentials). Outline (see [docs/external-secrets-notes.md](docs/external-secrets-notes.md) for the
full walk-through, gotchas, and open questions):

1. Create the vault + secret; grant the service's app principal `get` on secrets.
2. Add a **federated credential** on the app for the Akka OIDC issuer + subject
   `system:serviceaccount:<project-id>:klx-<service-name>`.
   **The issuer must include the trailing slash** — `akka projects regions workload-identity-info`
   prints it without, which causes `AADSTS700211`.
3. Register the external secret: `akka secret external create azure <name> --key-vault-name … --tenant-id … --client-id … --object-name <secret> --object-type secret`.
4. Mount it via a service descriptor (no CLI flag) and `akka service apply`:
   ```yaml
   name: <service>
   service:
     image: …
     volumeMounts:
     - mountPath: /secrets/pulse-test-file
       externalSecret:
         provider: <name>
   ```

### OpenAPI Spec

```shell
curl http://localhost:9000/pulse/openapi.yaml
```

## Multi-Region Testing

The service is designed for multi-region validation (replication, failover, recovery, and
cross-region topic publishing). The health endpoint reports the current region identity:

```shell
curl http://localhost:9000/pulse/health
# { "status": "UP", "region": "<region>", ... }
```

Topic publishing uses origin-based conditional publishing: a producer runs in every region,
but in `origin-only` mode (default, set via `pulse.topic.publish-mode`) it publishes each
event only from its origin region, so the topic receives each event exactly once. See the
full scenario matrix and operational procedures in
[specs/002-multi-region-test-service/quickstart.md](specs/002-multi-region-test-service/quickstart.md).

Topic scenarios need a message broker. The topic components (`SyntheticTopicProducer` and
`SyntheticTopicConsumer`) are disabled by default (`pulse.topic.enabled = false`) so the
service starts in projects without a broker. Enable them and start a local broker only when
exercising topics:

```shell
PULSE_TOPIC_ENABLED=true mvn compile exec:java -Dakka.javasdk.dev-mode.eventing.support=kafka
```

On the platform, set `PULSE_TOPIC_ENABLED=true` only in projects that have a message broker
configured.

## Security Scanning

The project uses Snyk vulnerability scanning. The application SDK dependency scan runs automatically in CI; the remaining scans are manually triggered and split into two workflows: recommended scans (Akka-only runtime image, runtime manifest dependencies, filtered application pom) and alternative scans kept for reference. See [docs/vulnerability-scanning.md](docs/vulnerability-scanning.md) for details.

## Deploy

Build container image:

```shell
mvn clean install -DskipTests
```

Deploy:

```shell
akka service deploy akka-pulse akka-pulse:tag-name --push
```
