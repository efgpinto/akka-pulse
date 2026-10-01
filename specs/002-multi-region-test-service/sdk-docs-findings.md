# Findings: Akka SDK multi-region docs and APIs (2026-09-29)

**Scope**: the Akka SDK docs (as of 2026-09-23), the SDK 3.6.5 APIs and the `akka` CLI, as seen by
a service developer and operator. Evidence is a result from `results-2026-09.md` (marked "Test"),
or the public SDK API and its Javadoc.

Findings are ordered by impact. Each has a proposed change.

## Docs

### D1. The replication partial describes only pinned-region routing (high)

- **Where**: `sdk/partials/mutli-region-replication.adoc:11`, `:21`, `:33`. Included by
  `sdk/pages/event-sourced-entities.adoc`, `key-value-entities.adoc` and `workflows.adoc`.
- **Claim**: write requests "are routed to the primary region". For read-your-writes, "use
  `Effect` ... Then the request will be routed to the primary".
- **Problem**: under `request-region`, the documented default, a write in a non-primary region
  moves the primary to that region. It is not routed. A command declared with a plain `Effect`
  counts as a write even when it only reads, so the advice at `:33` moves the primary, and the next
  write in the original region moves it back.
- **Evidence**: Test H4 and H5. Request-region: a write in B was handled in B (primary switch,
  50-67 ms server time); a plain `Effect` read in B took 51-55 ms and the next write in A
  54-57 ms (switch back). A `ReadOnlyEffect` read took 1-2 ms. Pinned-region: both were forwarded
  to A (about 42 ms).
- **Proposed**: describe both modes in the partial. Under `request-region`, a command declared
  with `Effect` in a non-primary region moves the primary to that region after synchronizing
  state. Under `pinned-region`, it is forwarded to the project primary. State the cost of using
  `Effect` on a read under `request-region`. Workflows: see D6.

### D2. The request-region availability rule has no stated consequences (high)

- **Where**: `operations/pages/regions/setup.adoc:68`.
- **Claim**: "all regions must be available when the first write request is made to an entity or
  when switching primary region".
- **Problem**: the page does not say what happens otherwise, or when it happens.
  - Every deploy, rolling restart and region add made first writes to new entities (and primary
    switches) fail in all regions for 2-4 minutes. This also happened with 2 instances per region
    and after the service reported Ready.
  - The caller gets `Unexpected error [<id>]` (HTTP 500) after about 5.4 s, with no hint of the
    cause, and the write is not retried. The service logs do not show the cause either.
  - Writes to entities whose primary is already local were not affected.
  - Under `pinned-region`, only the restarting non-primary region was affected, for about
    1 minute.
  - Concurrent writes to one entity from two regions failed 23 of 40 times under
    `request-region` and 0 of 40 under `pinned-region`.
- **Evidence**: Test H10, H13, H14.
- **Proposed**: a "Failure modes" subsection under selecting primary. It should say:
  - when the wait for other regions happens (a new entity, a primary switch)
  - that the wait is limited to 5 seconds and cannot be configured
  - the error the caller sees
  - that it happens during deploys and restarts of any region
  - that clients must retry such writes, and that `pinned-region` avoids it

  Add the same warning to the SDK partial (D1). See also P1.

### D3. Consumer behaviour when a region is added later is not documented (high)

- **Where**: `sdk/pages/consuming-producing.adoc:410-416`.
- **Claim**: a Consumer runs in every region; use `hasLocalOrigin()` or `originRegion()`.
- **Problem**: a region added to a running project processes the full event history through its
  Consumers. Every unguarded side effect runs again for every past event. The page also does not
  say that `hasLocalOrigin()` is true for topic and service-stream messages in every region (D4).
- **Evidence**: Test H13 r2: each of 3 events written before the region existed was processed
  once by the new region's Consumer. Test H1: an unguarded side effect ran once per region.
- **Proposed**: add "Adding a region" to the multi-region section. Consumers in the new region
  start from the beginning of the event history. Guard side effects with `hasLocalOrigin()` for
  entity sources, and with idempotency for topic and service-stream sources.

### D4. `hasLocalOrigin()` Javadoc contradicts the API behaviour (high)

- **Where**: `akka-javasdk/src/main/java/akka/javasdk/OriginAwareContext.java:40-41` vs `:47-52`.
- **Claim**: "This method will always return `false` when consuming messages from another service
  ... or from a topic".
- **Problem**:
  - Topic and service-stream messages carry no origin region.
  - The default implementation, `originRegion().stream().allMatch(...)`, returns `true` when the
    origin region is empty.
  - So a user who guards a topic Consumer with `hasLocalOrigin()` gets no guard at all.
- **Evidence**: the SDK source above, plus `RegionProbeConsumerIntegrationTest` (an empty origin
  region gives `true`). Not tested on a deployment (no broker).
- **Proposed**: decide which is intended.
  - If the behaviour is intended, fix the Javadoc and say explicitly that the method is not a
    guard for topic and service-stream sources.
  - If the Javadoc is intended, fix the behaviour. Dev mode relies on the current behaviour
    (comment at `:50`).

### D5. Timers are documented as cluster-unique, with no region behaviour (medium)

- **Where**: `sdk/pages/timed-actions.adoc:81`.
- **Claim**: "Timers are unique by name across the entire cluster".
- **Problem**: timers are unique within a region only.
  - They are not replicated and do not move to another region if their region is lost.
  - A timer scheduled from a Consumer is registered in every region and fires in every region
    under the same name.
- **Evidence**: Test H3 (3+3 firings, 3+0 guarded; a timer scheduled from an endpoint fired once),
  H13 (a pending timer was not copied to a new region).
- **Proposed**: a "Multi-region" section. It should say:
  - timers are per region
  - scheduling from a Consumer needs a `hasLocalOrigin()` guard
  - pending timers do not move to another region if their region is lost

  Change "entire cluster" to "within a region".

### D6. Workflow multi-region behaviour is only on an operations page (medium)

- **Where**: `sdk/pages/workflows.adoc:361-362` includes the generic partial.
  `operations/pages/operator-best-practices.adoc:18` is the only page that says a Workflow keeps
  its primary in the creating region.
- **Problem**: the SDK Workflow page does not say that:
  - the primary stays in the creating region in both modes
  - commands from other regions are forwarded there
  - step and pause timers fire only there
- **Evidence**: Test H6 (a signal sent to B was handled in A, 66-203 ms; pause timeouts fired in A,
  also for a workflow created before a region add).
- **Proposed**: a Workflow-specific replication section instead of, or after, the shared partial.

### D7. The pinned-region illustration shows request-region primaries (medium)

- **Where**: `concepts/pages/multi-region.adoc:43` ("Illustrating entities with pinned region
  selection") and `:57-67`.
- **Problem**:
  - The text gives Alice a primary in Los Angeles and Bob a primary in the UK. That is
    per-entity placement, which is request-region behaviour.
  - It then forwards writes, which is pinned-region behaviour.
  - Under pinned-region, all entities share one primary. Under request-region, the write moves
    the primary.
- **Proposed**: either use one project primary for both users, or retitle the section for
  request-region and show the primary moving.

### D8. Concept pages describe durable write routing and user CRDTs (medium)

- **Where**: `concepts/pages/state-model.adoc:50`, `:64`, `:98`, `:100`;
  `concepts/pages/multi-region.adoc:73-75`; `concepts/pages/distributed-systems.adoc:57`, `:86`.
- **Claims**:
  - writes are forwarded to the origin region
  - routing is "asynchronous and durable ... network partitions will not stop the write from being
    queued"
  - for multi-writer use, "you must implement a CRDT"
  - replication is "based on CRDTs"
- **Problem**:
  - Under `request-region`, writes are not forwarded (D1).
  - A write that needs another region fails after 5 s; it is not queued (D2).
  - The SDK has no CRDT entity type that services can use, and the service descriptor only
    supports `replicated-read` (`reference/pages/descriptors/service-descriptor.adoc:376`).
- **Proposed**: align `state-model.adoc` with `multi-region.adoc:21-40`, which is correct. Remove
  the CRDT and replicated-write passages, or mark them as not available to services.

### D9. Autonomous Agents page may be out of date (medium)

- **Where**: `sdk/pages/autonomous-agents.adoc:161`.
- **Claim**: "Autonomous Agents are not replicated to other regions".
- **Problem**: recent runtime releases appear to add replication of Autonomous Agent state, so
  this sentence may no longer hold for SDK 3.6.5.
- **Evidence**: not tested here.
- **Proposed**: confirm the current behaviour, then update the page.

### D10. Views page: wrong context and "identical Views" (low)

- **Where**: `sdk/pages/views.adoc:392`, `:394`.
- **Problem**:
  - `:394` says the local region comes from `messageContext().selfRegion()`. In a `TableUpdater`
    it is `updateContext().selfRegion()`.
  - `:392` says "identical Views are built in each region". That does not hold for entities with
    a replication filter (no rows in excluded regions), or for updaters that compute region-local
    values.
- **Evidence**: Test H8 (per-region `builtIn`, `viewUpdatedAt`), H9 (no row in B).
- **Proposed**: fix the accessor; qualify the sentence and link to the replication filter
  section.

### D11. Region typo in the replication filter sections (low)

- **Where**: `sdk/pages/event-sourced-entities.adoc:222`, `sdk/pages/key-value-entities.adoc:171`.
- **Problem**: "before handling the command in `gcp-us-east1`" should be `aws-us-east-2`, the
  region that received the command.

### D12. Broken `selecting-primary` links (low)

- **Where**: `concepts/pages/multi-region.adoc:41`, `operations/pages/operator-best-practices.adoc:7`,
  `reference/pages/descriptors/service-descriptor.adoc:368`.
- **Problem**: they point to `operations:regions/index.adoc#selecting-primary`; the anchor is in
  `operations/pages/regions/setup.adoc:40`.

### D13. Adding a region to a running project is not described (low)

- **Where**: `operations/pages/regions/` pages.
- **Problem**: adding a region:
  - rolled the stateful services in the existing region
  - reported the new region Ready about 40 s before its data arrived (reads returned empty state,
    workflows "not found")
  - left routes with the original region's hostname, synced to the new region as `NotConfigured`
    (`akka services expose --region <new>` then fails with "route already exists")
  - caused the entity-creation failures in D2
- **Evidence**: Test H13.
- **Proposed**: an "Adding a region to a running project" how-to. It should cover:
  - the expected rollout
  - the empty-read window
  - route steps (a regional route with the generated hostname, or a global hostname)
  - the Consumer replay (D3)

### D14. Smaller issues (low)

- `operations/pages/regions/setup.adoc:43` lists "Event Sourced Entities and Workflows"; Key Value
  Entities are missing.
- `reference/pages/descriptors/service-descriptor.adoc:391` shows `PrimarySelectionMode`;
  `setup.adoc` and the CLI use `primarySelectionMode`.
- `sdk/partials/mutli-region-replication.adoc`: file name typo ("mutli").
- `ROOT/pages/akka-orchestration.adoc:47` says Workflows run "active-active" with failover. Each
  Workflow has one primary, in the creating region; commands forwarded to a lost region fail.
- `operations/pages/regions/setup.adoc:70-72`: in `none` mode, writes fail with the generic
  `Unexpected error` (HTTP 500). The page should say what the caller sees. Test OP-B.

## SDK API and Javadoc

- **C1**: D4 (`hasLocalOrigin()`).
- **C2**: the error for a Key Value Entity that sets a replication filter without
  `@EnableReplicationFilter` says "the EventSourcedEntity class must be annotated with
  @EnableReplicationFilter".
- **C3**: `KeyValueEntity.java:409` Javadoc: "State us by default replicated".
- **C4**: `@EnableReplicationFilter` Javadoc mentions only Event Sourced Entities; it also applies
  to Key Value Entities.
- **C5**: the TestKit gives `selfRegion() == ""` and an empty `originRegion()` in every context,
  so region-dependent logic (`hasLocalOrigin()` guards for side effects and timers) cannot be
  tested. Proposed: TestKit settings to set the self region, and the origin region of published
  test messages.
- **C6**: a View query that uses `AS rows` fails only at service start (`AK-00106 ... rows is a
  reserved word`), not at compile time.

## CLI and operations

- **P1**: the failed write in D2 returns `Unexpected error` with no cause, and nothing in
  `akka services logs` names the region that did not answer. Proposed: return a specific error
  (or an AK code) to the caller and in the service log, and consider a bounded retry, or holding
  readiness until replication between regions has recovered.
- **P2**: `akka project settings down-region` reports that a service with no `replication` block
  defaults to `pinned-region`, while services without that block run with `request-region`. The
  effective mode is easy to misread. Proposed: align the message with the runtime default, and
  recommend setting the mode explicitly in the service descriptor.
- **P3**: `akka services restart --region <r>` restarted all regions ("This operation will be
  synced to all regions"). Proposed: document this, or restart only the named region.
- **P4**: `akka projects regions remove` has no non-interactive confirmation; the prompt defaults
  to "No". Proposed: a `--force` flag, consistent with other destructive commands.
