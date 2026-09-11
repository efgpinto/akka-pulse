# Project network policy — guide and validated behavior

Akka projects have exactly one network policy, named `default` on the platform. It controls
which ingress and egress traffic is allowed to and from the services in the project. The
behavior below was validated on 2026-09-11 against project `eduardo-playground-dev` using this
repository's services (details in [How this was validated](#how-this-was-validated)).

Not yet in the public docs; the `akka` CLI (3.0.74+) is the source of truth.

## Model

- One policy per project. It can be configured, not created or deleted.
- Traffic between services in the same project, and traffic required by Akka infrastructure,
  is always allowed regardless of the policy.
- Route (internet) traffic is **not** affected by ingress rules: the platform ingress counts
  as infrastructure. Ingress rules only govern direct pod traffic, in practice cross-project
  service calls. Use routes plus service ACLs to control internet exposure.
- A rule with no ports allows all ports. A rule with no peers allows all sources or
  destinations.
- Egress rules **extend** a platform baseline outbound allow-list (80, 443, 8080, 9092,
  5432, and about 50 others) that always stays in effect. Declaring a policy without the
  baseline ports does not revoke them: the policy can only allow additional traffic, not
  restrict the baseline.
- Changes take effect on running services within seconds. The CLI prints "will not be applied
  to existing services until they are restarted", but in practice no restart was needed.

## Default policy

A new project shows no ingress rules and one egress rule mirroring the platform baseline
allow-list. Outbound connections on ports outside the allowed set are blocked; the caller
sees the request fail (an HTTP client behind the runtime's egress proxy gets a
`504 Gateway Timeout`).

## CLI

```shell
akka project network-policy get
akka project network-policy add egress-rule --port 666
akka project network-policy add egress-rule --port 666 --to cidr:35.180.139.74/32
akka project network-policy add ingress-rule --from cidr:203.0.113.0/24
akka project network-policy remove egress-rule 2       # 1-based index from `get`
akka project network-policy export -f netpol.yaml
akka project network-policy apply -f netpol.yaml
```

Port forms: `N`, `N/PROTO` (TCP, UDP, SCTP), `N-M` range, `akka-service`.
Peer forms: `service:NAME`, `service:NAME@PROJECT_ID`, `project:PROJECT_ID`,
`cidr:CIDR[,EXCEPT...]`.

## Descriptor

`export`/`apply` round-trip works, and the document is accepted inside a multi-document
project descriptor by plain `akka project apply`. `deploy/project-full.yaml` declares an
example egress policy with one hardcoded allowed port (666/TCP): the egress probe passes on
666 and on baseline ports, and fails on everything else (667, 999, ...).

```yaml
resource: NetworkPolicy
resourceVersion: v1
spec:
  egress:
  - ports:
    - port: 80
      protocol: TCP
```

## Testing egress from this service

pulse-core has an outbound connectivity probe, backed by `portquiz.net`, an internet server
that answers plain HTTP on every TCP port:

```shell
# Blocked by default (666 is not in the platform allow-list)
curl "https://<pulse-core-host>/pulse/probes/egress?port=666"
# {"probe":"egress","passed":false,...,"evidence":"... 504 Gateway Timeout"}

akka project network-policy add egress-rule --port 666

# Passes within ~15 seconds, no restart
curl "https://<pulse-core-host>/pulse/probes/egress?port=666"
# {"probe":"egress","passed":true,...}
```

Query parameters: `host` (default `portquiz.net`), `port` (default 80), `path` (default `/`).
A blocked connection is a result (`passed: false`), never a 5xx from the probe itself.

## How this was validated

- **Baseline:** ports 80 and 8080 pass, 666 blocked, matching the default allow-list.
- **Egress port rule:** `add egress-rule --port 666` unblocked 666 in ~15 seconds.
- **Egress peer matching:** with the rule scoped `--to cidr:<wrong>/24`, 666 stayed blocked;
  scoped to portquiz's `/32`, it passed.
- **Ingress vs routes:** with an ingress rule allowing only a bogus CIDR, the public route
  still returned 200 from two different source IPs.
- **Intra-project exemption:** pulse-peer's s2s probes against pulse-core passed under that
  same restrictive ingress rule.
- **Descriptor:** export / edit / apply round-trip verified; a `NetworkPolicy` document in a
  project descriptor applies cleanly via `akka project apply` and reports `(unchanged)` when
  it matches the live policy.
- **Baseline is additive:** applying a policy whose only egress rule allows 666/TCP left
  baseline ports (80, 8080) reachable while blocking 667, 999, and 2000. Project rules
  extend the baseline; they cannot revoke it.
- **Not tested:** cross-project traffic rules (`service:NAME@PROJECT_ID`, `project:ID` peers)
  — requires a service deployed in a second project.

## Rollout gotcha: 403 on all network-policy commands

On a project created before the feature rolled out, every `network-policy` command can fail
with a "forbidden" error, even for a project admin. The project's access permissions are
refreshed when its role bindings change. Self-service fix: add (and then remove) any role
binding on the project, then retry.
