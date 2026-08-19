# Research: Multi-Region Test Scenarios

**Date**: 2026-08-05
**Feature**: `002-multi-region-test-service`

This document resolves the unknowns for planning. Each item lists the decision,
the rationale, and the alternatives considered. All findings come from the Akka
reference docs in `akka-context/`.

---

## 1. Primary selection mode configuration (`pinned-region` / `request-region`)

**Decision**: The primary selection mode is a **deployment concern**, set in the
service descriptor, not in application code or `application.conf`. Path:

```yaml
service:
  replication:
    mode: replicated-read
    replicatedRead:
      primarySelectionMode: pinned-region   # or request-region (default) | none
```

Applied with `akka service apply`. For `pinned-region`, the project primary is set
with `akka project regions set-primary <region>`. Switching modes must pass through
`none` (read-only) first.

**Rationale**: FR-012 requires the service to support both modes. The docs
(`akka-context/concepts/multi-region.html.md`, `akka-context/operations/regions/setup.html.md`)
show this is inherent Akka platform behavior — no code change is needed to support
either mode. The service works with both out of the box.

**Impact on the spec**: The spec assumption "primary selection mode defaults to
`pinned-region`" is refined: the Akka **default is `request-region`**. The service
must be deployed with `pinned-region` explicitly if that is the desired default.
This is captured in `quickstart.md` as a deploy step, not as code.

**Alternatives considered**: An `application.conf` key — rejected; the mode is not
an SDK config key, it is a service-descriptor / project setting.

---

## 2. Region identity at runtime

**Decision**: Read the current ("self") region from the component contexts:

| Component | API |
|-----------|-----|
| Event Sourced Entity | `commandContext().selfRegion()` |
| Key Value Entity | `commandContext().selfRegion()` |
| Consumer | `messageContext().selfRegion()`, `originRegion()`, `hasLocalOrigin()` |
| View (TableUpdater) | `updateContext().originRegion()`, `hasLocalOrigin()` |
| **HTTP Endpoint** | **no documented region API** |
| **Workflow** | **no documented region API** |

**Rationale**: FR-014 requires the health endpoint to report the current region.
HTTP endpoints have **no** documented self-region accessor (`RequestContext` exposes
only headers, query params, JWT, tracing — `akka-context/sdk/http-endpoints.html.md`).
The health endpoint already does a write/read round-trip to `HealthCheckEntity`
(a Key Value Entity). A KVE **can** read `commandContext().selfRegion()`. So the
region is captured inside `HealthCheckEntity`, stored in its state, and returned to
the endpoint, which surfaces it in the health response.

**"Replication status"**: There is no code API for replication lag/status. It is
observed in the Control Tower replication section
(`akka-context/operations/regions/setup.html.md`). FR-014 is therefore split:
region identity is reported in code (via the KVE); replication lag/status is an
operational metric via Control Tower, documented in `quickstart.md`.

**Alternatives considered**: Reading region from an endpoint header — rejected; no
such documented header. Injecting region via env var — brittle and redundant since
the KVE exposes it directly.

---

## 3. Replication behavior per component type (replicated-reads mode)

**Decision / facts** used for the design:

| Component | Replicated? | Behavior |
|-----------|-------------|----------|
| Event Sourced Entity | Yes (stateful, primary) | Writes routed to primary; reads from any region; events replicate to all regions |
| Key Value Entity | Yes (stateful, primary) | Same model; state changes replicate; empty/deleted state replicates |
| Workflow | Yes (stateful, primary) | Same model; durable, resumes in failover region |
| View | No (runs per region) | Built independently in **every** region from locally-replicated events |
| Consumer | No (runs per region) | Runs in **every** region; processes locally-replicated events |
| Timed Action | **Not documented** | No multi-region guarantees documented |

**Rationale**: This is the crux of the topic-publishing concern (item 4) and of the
consumer idempotency requirement (FR-005). Because Consumers run in every region, a
naive producer publishes once per region. Views and Consumers materialize per region.

**Impact — FR-006 (Timed Action fires in one region only)**: The docs give **no**
multi-region guarantee for Timed Actions. This is a **known gap**. The plan keeps
FR-006 as an operational observation (AL-9): validate actual behavior on a two-region
deploy rather than assert a documented guarantee. Flagged in `plan.md` risks.

**Alternatives considered**: Forcing fully-consistent reads with `Effect` instead of
`ReadOnlyEffect` — noted as an option for a "no stale read" scenario (AL-9), not the
default.

---

## 4. Topic publishing and origin-based conditional publishing

**Decision**: Add a producer `SyntheticTopicProducer`:

```java
@Component(id = "synthetic-topic-producer")
@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)
@Produce.ToTopic("synthetic-record-events")
public class SyntheticTopicProducer extends Consumer {
  // origin-only mode (default):
  //   if (!messageContext().hasLocalOrigin()) return effects().ignore();
  // build message + metadata, then effects().produce(message, metadata)
}
```

- Skip publishing with `effects().ignore()` when the event is not local-origin.
- Attach subject + origin region via
  `Metadata.EMPTY.add("ce-subject", recordId).add("ce-origin-region", region)`.
- Downstream `SyntheticTopicConsumer` reads with `@Consume.FromTopic("synthetic-record-events")`
  and increments a counter so message counts (and duplicates) are observable.

**Rationale**: Directly implements FR-007/FR-008/FR-010 and the US6 concern.
`hasLocalOrigin()` is the documented origin filter
(`akka-context/sdk/consuming-producing.html.md`).

**Build-time mode selection (FR-009)**: The publish mode (`origin-only` vs
`every-region`) is read once at startup from `application.conf`
(`pulse.topic.publish-mode`) and provided to the producer via a `PulseTopicSettings`
dependency created in `Bootstrap` (which already implements `ServiceSetup`).
In `every-region` mode the producer skips the `hasLocalOrigin()` guard and always
publishes. Changing the mode requires editing config and redeploying — matching the
spec's "not switchable at runtime."

**Known limitation — DI-5 / US6 scenario 4 (origin region fails before publishing)**:
In `origin-only` mode, if the origin region goes down *before* its producer publishes,
no other region will publish that event (`hasLocalOrigin()` is false everywhere else),
so the message is lost. True no-loss-on-origin-failure would require publishing based
on "am I the current primary region" rather than "did the event originate locally,"
which the SDK does not expose directly to a Consumer. **Decision**: keep the simple
`hasLocalOrigin()` design as the documented default; treat DI-5 as a scenario that
*reveals* this limitation rather than one the code fully prevents. Documented in
`plan.md` risks and `quickstart.md`.

**Alternatives considered**:
- Extend the existing `SyntheticEventConsumer` to also produce — rejected; violates
  single-responsibility (it owns the consumer counter). A dedicated producer is clearer.
- `effects().done()` instead of `effects().ignore()` to skip — both avoid producing;
  `ignore()` is the documented skip-and-continue and reads as intent.

---

## 5. Message broker / eventing configuration

**Decision**:
- **Tests**: no broker. Use the TestKit mocked topic —
  `TestKit.Settings.DEFAULT.withTopicOutgoingMessages("synthetic-record-events")`
  and `.withTopicIncomingMessages(...)`, with `getTopicOutgoingMessages(...)` /
  `getTopicIncomingMessages(...)` handles.
- **Local run**: enable dev-mode eventing only when exercising topics —
  `akka.javasdk.dev-mode.eventing.support = "kafka"` (broker at `localhost:9092`) or
  the Google Pub/Sub emulator. Left at `none` by default so existing local runs are
  unaffected.
- **Deployed**: broker configured once at the project level via
  `akka projects config set broker --broker-service kafka|google-pubsub ...`.
  Topic names in code reference that project broker. Kafka topics must be created
  ahead of time.

**Rationale**: Keeps the default developer experience unchanged (FR keeps existing
behavior), makes tests broker-free and deterministic, and documents the deploy path.

**Alternatives considered**: Requiring a broker for all local runs — rejected;
unnecessary friction for the non-topic scenarios.

---

## 6. Topic message counter: reuse vs new

**Decision**: Add a dedicated `TopicMessageCounter` domain record and
`TopicMessageCounterEntity` (KVE), separate from `ConsumerCounter`.

**Rationale**: The spec's `TopicMessageCounter` tracks `lastOriginRegion`, which
`ConsumerCounter` does not model. Keeping topic observability separate from the
existing consumer counter preserves single-responsibility (Constitution II) and lets
duplicate-publishing detection track the origin region.

**Alternatives considered**: Reuse `ConsumerCounterEntity` (Constitution IV,
simplicity) — rejected because it cannot record the origin region needed to observe
per-region duplicate publishing.
