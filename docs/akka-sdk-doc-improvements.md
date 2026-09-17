# Akka SDK / docs improvement notes

Issues found while wiring an Azure Key Vault external secret into the `akka-pulse` service
(external secrets → workload identity → file mount). Filed here so they can be reported upstream.

Reference doc: `akka-context/operations/projects/external-secrets.html.md`.

## 1. CLI command ordering for external secrets is wrong in the docs

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

## 2. Permission model for creating a vault is under-explained

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

## 3. Service-descriptor format vs project-descriptor format for the mount

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

## 4. Minor: `.env` is not auto-loaded locally

Not an SDK bug, but a common expectation gap: `mvn exec:java` (and the local runner) do not source
a `.env` file. A one-line note in the local-dev/config docs ("export env vars yourself:
`set -a; source .env; set +a`") would save confusion.
