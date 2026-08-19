# Topic Messaging Contract: Multi-Region Test Scenarios

**Date**: 2026-08-05
**Topic**: `synthetic-record-events`

## Producer: SyntheticTopicProducer

- Source: `@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)`
- Destination: `@Produce.ToTopic("synthetic-record-events")`
- Publishes a `SyntheticTopicMessage` per record event.

### Publishing modes (build-time, `pulse.topic.publish-mode`)

| Mode | Guard | Result |
|------|-------|--------|
| `origin-only` (default) | `if (!messageContext().hasLocalOrigin()) return effects().ignore();` | Exactly one message per event across all regions |
| `every-region` | no guard; always `produce(...)` | One message per region per event |

### Message payload (`SyntheticTopicMessage`, JSON)

```json
{
  "recordId": "test-1",
  "eventType": "record-created",
  "value": "hello",
  "originRegion": "gcp-us-east1"
}
```

### Message metadata (CloudEvents)

| Metadata key | Value | Purpose |
|--------------|-------|---------|
| `ce-subject` | recordId | Per-entity ordering; downstream subject |
| `ce-origin-region` | origin region | Observability of where the event originated |
| `ce-type` | fully-qualified `SyntheticTopicMessage` name | Downstream handler routing |
| `Content-Type` | `application/json` | Payload format |

Built as:
`Metadata.EMPTY.add("ce-subject", recordId).add("ce-origin-region", originRegion)`.

---

## Consumer: SyntheticTopicConsumer

- Source: `@Consume.FromTopic("synthetic-record-events")`
- Handler: `onMessage(SyntheticTopicMessage message)` → increments `TopicMessageCounterEntity`.
- Reads subject/metadata via `messageContext().eventSubject()` and message metadata.

---

## Delivery semantics

- At-least-once from the topic; the counter is the observable signal.
- `origin-only` mode is the mechanism that prevents per-region duplicate publishing.

## Known limitation (DI-5)

In `origin-only` mode, if the origin region fails **before** the producer publishes
an event, no other region republishes it (`hasLocalOrigin()` is false elsewhere), so
that one message is lost. This is an accepted trade-off of the simple origin filter;
DI-5 exercises and documents it rather than preventing it. See `research.md` §4.
