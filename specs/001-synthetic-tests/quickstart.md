# Quickstart: Synthetic Test Suite & Health Monitoring

## Prerequisites

- Java 21+
- Maven 3.9+
- Akka CLI installed

## Build & Run

```shell
mvn compile exec:java
```

## Verify Health

```shell
curl http://localhost:9000/pulse/health
```

Expected: `{"status":"UP","serviceName":"akka-pulse",...}`

## Exercise Components

### Event Sourced Entity
```shell
# Create
curl -X POST http://localhost:9000/pulse/records/test-1/create \
  -H "Content-Type: application/json" \
  -d '{"name":"my-record","value":"hello"}'

# Update
curl -X POST http://localhost:9000/pulse/records/test-1/update \
  -H "Content-Type: application/json" \
  -d '{"value":"updated"}'

# Query
curl http://localhost:9000/pulse/records/test-1
```

### Key Value Entity
```shell
# Set
curl -X POST http://localhost:9000/pulse/entries/entry-1 \
  -H "Content-Type: application/json" \
  -d '{"data":"my-payload"}'

# Get
curl http://localhost:9000/pulse/entries/entry-1

# Delete
curl -X DELETE http://localhost:9000/pulse/entries/entry-1
```

### View
```shell
curl http://localhost:9000/pulse/records/by-name/my-record
curl http://localhost:9000/pulse/records/all
```

### Workflow
```shell
# Normal flow
curl -X POST http://localhost:9000/pulse/workflows/wf-1/start \
  -H "Content-Type: application/json" \
  -d '{"input":"test","mode":"normal"}'

# Trigger failure/compensation
curl -X POST http://localhost:9000/pulse/workflows/wf-2/start \
  -H "Content-Type: application/json" \
  -d '{"input":"test","mode":"trigger-failure"}'

# Check status
curl http://localhost:9000/pulse/workflows/wf-1
```

### Consumer Counter
```shell
curl http://localhost:9000/pulse/consumers/synthetic-record-counter
```

### Timed Action
```shell
curl -X POST http://localhost:9000/pulse/timers/timer-1/schedule \
  -H "Content-Type: application/json" \
  -d '{"delaySeconds":5}'
```

## Test Configurable Delays

```shell
# Slow entity command (3 seconds)
curl -X POST http://localhost:9000/pulse/records/slow-1/create \
  -H "Content-Type: application/json" \
  -d '{"name":"slow-record","value":"test","delaySeconds":3}'

# Slow workflow step (10 seconds)
curl -X POST http://localhost:9000/pulse/workflows/slow-wf/start \
  -H "Content-Type: application/json" \
  -d '{"input":"test","mode":"normal","delaySeconds":10}'
```

## Generate Burst Traffic

```shell
# Burst 50 parallel requests to ESE
curl -X POST http://localhost:9000/pulse/burst \
  -H "Content-Type: application/json" \
  -d '{"target":"event-sourced-entity","count":50}'

# Burst with delay per request
curl -X POST http://localhost:9000/pulse/burst \
  -H "Content-Type: application/json" \
  -d '{"target":"key-value-entity","count":20,"delaySeconds":2}'
```

## OpenAPI Spec

```shell
curl http://localhost:9000/pulse/openapi.yaml
```

## Run Tests

```shell
# Unit tests
mvn test

# Integration tests
mvn verify
```
