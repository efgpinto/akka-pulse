# Using Azure Key Vault external secrets with Akka — decision & setup guide

Akka can consume secrets from Azure Key Vault (AKV) via **workload identity**: a service presents an
OIDC token that Azure trusts (no stored credentials), and the secret is projected into the pod as a
**file** (external secrets mount as files only — never environment variables, never a Kubernetes
Secret). The application reads the file.

This guide covers the decisions to make, the trade-offs behind them, the setup steps, and the
behaviours worth knowing up front.

## Decision checklist

**Use RBAC.** It is Microsoft's recommended authorization model and access policies are legacy, so
the vault authorization model is effectively settled — which removes it as a decision. Grants are
managed through Azure RBAC: typically an Entra **group** for a team is given a Key Vault role to
manage secret values (`Key Vault Secrets Officer`), while each application reads via `Key Vault
Secrets User`.

| # | Decision | Options | Deciding question |
|---|----------|---------|-------------------|
| 1 | **Vault topology** | Shared / per-team · Vault-per-app | How automated is your networking (a private endpoint + firewall change per vault)? Isolation / compliance? Throttling volume? |
| 2 | **Secret shape** | One object per value · Bundle values in one object (`.env`/JSON) | Do the values rotate together and share access, or need independent rotation / audit? (Decide the object set up front.) |
| 3 | **Identity & federation** | One shared app · App per service / trust boundary | Should services be isolated from each other's secrets? (One federated credential per service either way.) |
| 4 | **Access scope** | Vault-wide · Per-secret · By name/tag (ABAC) | All secrets, one specific secret, or a class of secrets (name prefix / tag via an ABAC condition) — for least privilege? |

**Networking** (public vault vs private endpoint/firewall) is often decided by the organisation and
feeds the topology decision.

**Common default:** a single **RBAC** vault — grant shared secrets **vault-wide** and
service-private secrets **per-secret** (or by ABAC name/tag condition). Split into more vaults only
for blast-radius, networking, or throttling reasons — not because of the authorization model.

---

## Reference

### Authorization: RBAC

RBAC is a **superset** of access policies — it does vault-wide grants *and* per-secret scoping. The
roles you'll use:

- **`Key Vault Secrets User`** — read (`get`/`list`). What each application gets; it's all the
  runtime needs (it only ever reads at mount time).
- **`Key Vault Secrets Officer`** — read/write secret values. What a team (Entra group) gets to
  manage secrets.

An RBAC grant is an IAM **role assignment**, which requires `User Access Administrator` / `Owner` /
`Role Based Access Control Administrator`. Grant a team's Entra group the appropriate role centrally
so this isn't per-grant friction.

### Access scope — vault-wide, per-secret, or by name/tag (ABAC)

The **scope** of the role assignment sets breadth:

| Scope | Application can read |
|-------|----------------------|
| `…/vaults/<vault>` | **all** secrets in the vault |
| `…/vaults/<vault>/secrets/<name>` | just that one secret (one assignment per secret) |
| resource group / subscription | all vaults in that scope |

```
# all secrets in the vault:
az role assignment create --role "Key Vault Secrets User" --assignee <appId> --scope <vaultId>
# one secret only:
az role assignment create --role "Key Vault Secrets User" --assignee <appId> --scope <vaultId>/secrets/<name>
```

There is **no intermediate "group of secrets" scope**, so carving access purely by scope means
**one assignment per secret**. To grant a **class** of secrets in a single assignment, use **ABAC
conditions**: a condition on the role assignment can match secrets by **name prefix** or **tag** —
e.g. *"this assignment applies only to secrets whose name starts with `shared-`"* (or tagged
`shared=true`). This is how you model **"some secrets shared, others private" within one vault**
without a per-secret assignment for each. (Confirm ABAC condition support is available in your
tenant.) Assign to an **Entra group** rather than per application, so the principal side isn't
per-app toil either.

### Shared vault vs vault-per-app

| Aspect | Shared / per-team vault | Vault per app |
|--------|-------------------------|---------------|
| Isolation / blast radius | relies on per-secret RBAC | strong (vault is the boundary) |
| Throttling | shared budget (noisy-neighbour) | isolated per app (per-vault limits) |
| Networking (private endpoint/firewall) | set up **once** | **one change request per vault** |
| Access model | per-secret / ABAC RBAC | grant whole vault (simple) |
| Management overhead | fewer vaults, shared lifecycle | more vaults |
| Best when | heavy manual network process; cost of many private endpoints | IaC-automated networking; strict isolation/compliance |

- **Microsoft's default guidance** is vault-per-app-per-environment (isolation + throttling).
- A production vault is usually locked down with a firewall and/or **Private Endpoint**, configured
  **per vault**. Where those go through a central network team as ticketed changes, N vaults ⇒ **N
  change requests** (plus private IPs, DNS records, PE cost); a shared vault sets that up **once**.
  This concern largely disappears when private endpoints are provisioned via IaC/pipelines.
- **Cost** is not the driver — vaults bill per operation, not per vault.
- If the vault is private-endpoint-locked, the Akka runtime's egress must be network-reachable to
  the vault; arranging that **once for a shared vault** is far less work than per-vault for a fleet.

### The authorization unit is the app, not the service

A service has no vault permissions directly — it authenticates **as an app** (an Entra app
registration) via workload-identity federation, and the **app** holds the vault access. So to give
different services different access, use **different apps** and grant each only what it needs. Each
project/environment has its own OIDC issuer and per-service identities.

### Object types & storing multiple values in one object

An object's type is `secret`, `key`, or `cert`. Only `secret` is an opaque string, so only `secret`
can bundle multiple values:

| Object type | Holds | Multiple values? |
|-------------|-------|------------------|
| `secret` | opaque string (≤25 KB) | Yes — pack JSON/properties, parse in the app |
| `key` | one cryptographic key (RSA/EC) | No — 1 per object |
| `cert` | one X.509 certificate | No — 1 per object (the cert carries its own key + chain) |

- **Bundling in a `secret`:** store JSON or `.env` content; it mounts as one file the app parses.
  JSON is valid HOCON, so it maps directly into a `Config`.
- **Certificates** expose three forms under one name — the public cert, the private key, and the
  full PEM/PFX (cert + key + chain). Mounting the certificate as a `secret` gives the PEM in one
  file and keeps auto-renewal.
- **Do not** stuff PEM key/cert material into a plain `secret` blob — you lose HSM/non-exportable
  key protection, certificate lifecycle/renewal, and per-item rotation and audit. Keep keys and
  certificates as their own object types.

### The external secret is a reference, not a vault write

Registering an external secret in Akka does **not** create anything in Key Vault — it records a
reference to an *existing* AKV object. Three distinct roles are involved:

| Who | Action | Vault permission |
|-----|--------|------------------|
| Platform admin | register the external secret reference | none (never touches the vault) |
| Secret owner / team | create the actual secret **value** in AKV | write (`Secrets Officer`) |
| The service | read the value → file at pod start | read (`Secrets User`) |

Corollary: registering a reference to a secret that doesn't exist yet succeeds, but the **mount
fails** at pod start (nothing to read) until the value is created.

---

## Setup steps

1. **Create the vault and the secret** (`Key Vault Contributor` to create the vault; a data-plane
   role such as `Key Vault Secrets Officer` to write values):
   ```
   az keyvault create -n <vault> -g <rg> -l <location> --enable-rbac-authorization true
   az keyvault secret set --vault-name <vault> --name <secret> --value <value>
   ```
2. **Grant the app read access** (RBAC role assignment; vault-wide, per-secret, or with an ABAC
   condition):
   ```
   az role assignment create --role "Key Vault Secrets User" --assignee <appId> --scope <vaultId>
   ```
3. **Federate the credential** — the trust link between the Akka runtime identity and the app.
   Obtain the issuer and subject from `akka projects regions workload-identity-info`, then create a
   federated identity credential on the app:
   - issuer: the value from the command above — **include the trailing slash** (see Notes);
   - subject: `system:serviceaccount:<project-id>:klx-<service-name>`;
   - audience: `api://AzureADTokenExchange`.
4. **Register the external secret** in the Akka project:
   ```
   akka secret external create azure <name> --key-vault-name <vault> \
     --tenant-id <tenant> --client-id <appId> --object-name <secret> --object-type secret
   ```
5. **Mount it** in the service via a service descriptor and apply it:
   ```yaml
   name: <service>
   service:
     image: <image>
     volumeMounts:
     - mountPath: /secrets/<dir>
       externalSecret: { provider: <name> }
   ```
   The secret is then readable at `/secrets/<dir>/<secret>`.

---

## Notes & behaviours to know

- **Federated-credential issuer must include the trailing slash.** The token the pod presents uses
  an issuer ending in `/`; if the federated credential is created without it, mounting fails with
  `AADSTS700211: No matching federated identity record`. After correcting it, the mount succeeds on
  the next automatic retry (no manual restart needed).
- **One federated credential per service.** Each service authenticates with its own identity
  (`klx-<service-name>`), so each needs its own federated credential on the app. A single wildcard
  credential covering all services is not available for this issuer type.
- **Granting an app is an IAM role assignment.** It requires `User Access Administrator` / `Owner` /
  `Role Based Access Control Administrator` — plan for whoever owns that (ideally a team Entra group
  granted the role centrally).

---

## Local development vs deployed

Deployed, secrets arrive as **files**. Locally there is no mount. To keep a single code path in both
environments, read the secret with a small **file-or-config** helper:

- read the mounted **file** if it exists (deployed), otherwise fall back to a **configuration value**
  (local) that is seeded from an environment variable, e.g. `myapp.secret = ${?MY_SECRET}`.

The application code is identical in both places — only the source differs, and local development
keeps using environment variables as before. For multiple values, put them in one `.env`/JSON secret
object, parse it once at startup, and read by key (identical locally via env seeding and deployed via
the file).

Note: external secrets **cannot** be exposed as environment variables (they are file-only), so pure
env-var injection is not possible once secrets come from AKV — the file-or-config helper is the
bridge. Also, `.env` files are not auto-loaded by local build tooling; source them into the shell
before running.
