# Contract: pulse-core internal API (s2s only)

## GET /pulse/internal/ping

ACL: `@Acl(allow = @Acl.Matcher(service = "pulse-peer"))` — only the pulse-peer service may call.
Internet and other services are denied (platform-enforced; expect 403).

**200 OK**:

```json
{
  "serviceName": "pulse-core",
  "region": "<self-region>",
  "timestamp": "2026-09-07T12:00:00Z"
}
```

## Service stream `synthetic-records`

Producer: pulse-core (`@Produce.ServiceStream(id = "synthetic-records")`,
`@Acl(allow = @Acl.Matcher(service = "*"))` — any service in the project may subscribe).

Messages (`PulseStreamEvent` from pulse-common):

| @TypeName | Payload |
|-----------|---------|
| `pulse-record-created` | `{ "name": "...", "value": "...", "createdAt": "..." }` |
| `pulse-record-updated` | `{ "value": "...", "version": 2, "updatedAt": "..." }` |

Subject: the originating `SyntheticRecordEntity` id.
