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

Topic scenarios need a message broker. Enable a local one only when exercising topics:

```shell
mvn compile exec:java -Dakka.javasdk.dev-mode.eventing.support=kafka
```

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
