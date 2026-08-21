# Akka SDK / docs improvement notes

Issues found while wiring an Azure Key Vault external secret into the `akka-pulse` service
(external secrets → workload identity → file mount). Filed here so they can be reported upstream.

Reference doc: `akka-context/operations/projects/external-secrets.html.md`.

## 1. Federated credential issuer is missing the trailing slash (highest impact)

`akka projects regions workload-identity-info` prints the OIDC issuer **without** a trailing
slash, e.g.:

```
https://centralus.oic.prod-aks.azure.com/<tenant>/<cluster-id>
```

But the token the running pod presents has the issuer **with** a trailing slash
(`…/<cluster-id>/`). Azure matches federated identity on an exact issuer/subject/audience tuple,
so creating the credential with the printed (no-slash) issuer fails at mount time with:

```
AADSTS700211: No matching federated identity record found for presented assertion issuer '…/'
```

The generated `params.json` snippet in the CLI output uses the no-slash issuer too, so a user who
copies it verbatim hits this. **Fix in docs/tooling:** print the issuer with the trailing slash
(or warn explicitly), since that is what the assertion carries.

## 2. CLI command ordering for external secrets is wrong in the docs

The docs show:

```
akka secret external azure create my-external-secret …
```

The actual CLI is `create` then the provider:

```
akka secret external create azure my-external-secret …
```

(Same for `aws`/`gcp`.) The flag names (`--key-vault-name`, `--tenant-id`, `--client-id`,
`--object-name`, `--object-type`) are correct.

## 3. Permission model for creating a vault is under-explained

The docs walk through `az keyvault set-policy …`, which requires the vault to use the
**access-policy** model. Two things worth calling out:

- **`Key Vault Administrator` is a data-plane role only.** It lets you read/write secret *values*
  but NOT create a vault (`Microsoft.KeyVault/vaults/write`) or grant other principals access.
  Creating the vault needs `Key Vault Contributor`/`Contributor`; granting via RBAC needs
  `User Access Administrator`/`Owner`. This is easy to get wrong and produces a confusing
  `AuthorizationFailed`.
- The docs only show the **access-policy** grant. An **RBAC** alternative
  (`az role assignment create --role "Key Vault Secrets User" --assignee <appId> --scope <vaultId>`)
  should be documented alongside, since RBAC is Azure's recommended default and new vaults are
  RBAC-enabled unless `--enable-rbac-authorization false` is passed.

## 4. Service-descriptor format vs project-descriptor format for the mount

The external-secrets doc shows the mount using the `resource: Service / metadata / spec` project
descriptor form. But `akka service export <svc>` emits the `name: / service:` form, and
`akka service apply` consumes that form. It would help to show the `volumeMounts` +
`externalSecret` block in **both** forms (or the `name/service` form, since that is what
`export`/`apply` round-trip). Working `name/service` example:

```yaml
name: my-service
service:
  image: …
  volumeMounts:
  - mountPath: /secrets/my-secret
    externalSecret:
      provider: my-external-secret
```

## 5. Objects added via `akka secret external update` don't sync into the mount (likely a bug)

Observed 2026-08-21. An external secret created with one object and later given a second object via
`akka secret external update azure <name> --object-name <second> …` shows **both** objects in
`akka secret external get <name>`, but a service mounting it only ever gets the **create-time**
object as a file. The `update`-added object is silently absent from the mounted directory — no
`FailedMount` event, it is simply never fetched.

Reproduced three ways: `pulse-akv-rbac` and `pulse-akv-rbac2` (create `client-password`, update
`app-config`) both mounted only `client-password`; a provider **created** with `app-config` mounted
it immediately. Restart, pause/resume, and re-applying the service did not help — the underlying
`SecretProviderClass` appears to be generated from the create-time state and not regenerated on
`update`.

**This contradicts the documentation.** `external-secrets.html.md` states *"Adding multiple objects
can be done by updating the secret after initial creation"* and describes the mount path as a
directory with one file per object — i.e. multiple objects are supposed to mount as multiple files.
Observed behavior does not match.

**Also:** `akka secret external create azure` accepts only a single object — passing `--object-name`
twice keeps the **last** one (overwrites, does not append). Combined with the update issue, a single
external secret can only ever mount **one** object.

**Impact / workaround:** to mount multiple secrets reliably, use one external secret per object each
at its own `mountPath` (verified working), or bundle multiple values into a single object
(`.env`/JSON). Docs should either fix the sync on `update`, or warn that `update`-added objects are
not picked up by mounts.

**Not a timing issue — confirmed 2026-08-21.** Patient test: created `pulse-akv-test`
(client-password), `update`-added `app-config`, then mounted it fresh and also restarted for a
pod that started ~7 min after the update. The mount listed **only `client-password`** both times
(verified via `GET /pulse/secrets/`). The `update` completed *before* the mount was applied, yet the
generated mount still omitted `app-config`. So the CLI path builds the mount from the create-time
object only; `update`-added objects never reach it.

**ROOT CAUSE NARROWED — it's the CLI `create`/`update` path, not the platform (confirmed
2026-08-21).** Declaring the same external secret with BOTH objects via `akka project apply` works
correctly — both objects mount and are readable:

```yaml
metadata: {name: pulse-akv-declared}
resource: ExternalSecret
resourceVersion: v1
spec:
  azure:
    clientId: …
    keyVaultName: …
    objects:
    - {name: client-password, type: secret}
    - {name: app-config,      type: secret}
    tenantId: …
```

`akka project apply -f` → mount `/secrets/pulse-test-file` contained **both** `client-password` and
`app-config` (verified via `GET /pulse/secrets/`, and both readable).

**Further refinement (2026-08-21):** `project apply` only works when the external secret is
**created fresh**. **Updating** an existing external secret's objects does **not** regenerate the
mount either — not via `akka secret external update`, and not via `akka project apply` on an
existing secret. Verified by changing an object reference (`client-password` → `shared-client-password`)
on an existing external secret via `project apply`: `get` reflected it, but the mount still showed
`client-password` after restart; only **delete + recreate** the external secret fixed it. So the
SecretProviderClass is generated at external-secret **create** and never regenerated on any update
path. Working: `create` (fresh), `delete`+recreate. Non-working: `update` (CLI) and `project apply`
on an existing external secret.

## 6. Minor: `.env` is not auto-loaded locally

Not an SDK bug, but a common expectation gap: `mvn exec:java` (and the local runner) do not source
a `.env` file. A one-line note in the local-dev/config docs ("export env vars yourself:
`set -a; source .env; set +a`") would save confusion.
