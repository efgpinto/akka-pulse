# Feature Specification: External Secrets Test Endpoint

**Feature Branch**: `003-external-secrets`
**Created**: 2026-08-20
**Status**: Draft
**Input**: User description: "I want to add an endpoint for testing external secrets, in a similar format as the rest of the project. I started a SecretEndpoint. See if that makes sense or how to improve it."

## Overview

This feature extends the Akka Pulse synthetic test suite. It adds a probe to verify that
external secrets reach a running service. A platform engineer deploys the service with secrets
injected two ways: as environment variables and as volume-mounted files. The endpoint reports
whether each secret arrived and looks correct.

The endpoint follows the same style as the other Pulse probes (health, entity, view). It is a
synthetic validation tool. It does not manage, store, or rotate secrets. Its only job is to
confirm that the platform delivered the injected secrets to the service instance.

A draft `SecretEndpoint` already exists. It reads a named environment variable, reads a named
file under a secrets directory, and lists all `PULSE_`-prefixed secrets. The draft returns the
full secret value in the response and is open to the public internet. This spec keeps that
behavior on purpose: the service is a synthetic test tool, the secrets under test are test
values, and returning the full value lets an engineer confirm the exact injected content.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Verify an environment-variable secret (Priority: P1)

As a platform engineer, I want to check a single secret injected as an environment variable, so
that I can confirm the platform delivered it to the running service.

**Why this priority**: Environment variables are the most common secret-injection method. A
single-secret check is the smallest useful slice. It gives an immediate pass or fail for one
named secret.

**Independent Test**: Deploy the service with a known environment secret. Call the probe with
that name. Confirm the response reports the secret as present. Call with an unknown name.
Confirm the response reports it as missing.

**Acceptance Scenarios**:

1. **Given** an environment secret named `PULSE_TEST_SECRET` is injected, **When** a user probes that name, **Then** the response reports the secret is present, names its source as the environment, and includes a read timestamp.
2. **Given** no environment secret with the requested name exists, **When** a user probes that name, **Then** the response reports the secret as not found.
3. **Given** an environment secret is present, **When** a user probes that name, **Then** the response includes the full secret value so the engineer can confirm the exact injected content.

---

### User Story 2 - Verify a file-mounted secret (Priority: P2)

As a platform engineer, I want to check a secret mounted as a file, so that I can confirm the
platform mounted the secret volume and populated it.

**Why this priority**: File-mounted secrets are the second common injection method. This story
validates the volume mount path in addition to the value delivery. It depends on the same probe
pattern as Story 1, so it comes second.

**Independent Test**: Deploy the service with a secret mounted as a file in the configured
secrets directory. Probe the file name. Confirm the response reports the file secret as present.
Probe a missing file name. Confirm the response reports it as not found.

**Acceptance Scenarios**:

1. **Given** a secret file exists in the configured secrets directory, **When** a user probes that file name, **Then** the response reports the secret is present and names its source as the file location.
2. **Given** no file with the requested name exists, **When** a user probes that name, **Then** the response reports the secret as not found.
3. **Given** the secrets directory is not mounted, **When** a user probes any file name, **Then** the response reports the secret as not found and does not fail the service.

---

### User Story 3 - List all Pulse test secrets (Priority: P3)

As a platform engineer, I want one call that lists every Pulse test secret from both sources, so
that I can validate a full secret-injection setup at a glance.

**Why this priority**: The list view is a convenience over Stories 1 and 2. It speeds up a full
deployment check. It is not required for the core probe to be useful, so it is lowest priority.

**Independent Test**: Deploy the service with several `PULSE_`-prefixed environment secrets and
several file secrets. Call the list probe. Confirm every injected secret appears with its source
and presence status.

**Acceptance Scenarios**:

1. **Given** several `PULSE_`-prefixed environment secrets and file secrets are injected, **When** a user calls the list probe, **Then** the response lists every one with its name and source.
2. **Given** no Pulse test secrets are injected, **When** a user calls the list probe, **Then** the response returns empty secret collections and still succeeds.

---

### Edge Cases

- What happens when a secret file exists but is empty? The probe reports it present with an empty value indicator.
- How does the system handle a secret file that cannot be read (permission error)? The probe reports a read error for that entry and does not fail the whole request.
- What happens when a requested environment-variable name is blank or malformed? The probe reports it as not found.
- What happens when the list probe finds a large number of matching secrets? The probe returns all matches; there is no expectation of a business-scale volume for a synthetic test.
- How does the probe behave across regions? The result reflects the secrets injected into the region that serves the request. Secret injection is a per-deployment concern, not replicated state.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST report whether a named environment-variable secret is present in the running service instance.
- **FR-002**: The system MUST report whether a named file secret is present in the configured secrets directory.
- **FR-003**: The system MUST report a list of all Pulse test secrets from both the environment and the file source.
- **FR-004**: Each secret result MUST include the secret name, the source (environment or file), a presence status, and a read timestamp.
- **FR-005**: The system MUST report a missing secret clearly and distinctly from a present secret. A missing secret MUST NOT be treated as an error that fails the service.
- **FR-006**: The system MUST return the full secret value for a present secret, so an engineer can confirm the exact injected content. This is acceptable because the service is a synthetic test tool operating on test secrets only.
- **FR-007**: The endpoint MUST follow the existing Pulse endpoint conventions: the `/pulse` path prefix, an access-control rule, and JSON responses shaped like the other probes.
- **FR-008**: The endpoint MUST be reachable from the public internet, matching the other Pulse probes, so an engineer can call it directly during a deployment check.
- **FR-009**: The system MUST use a configurable identifier for the environment-secret name prefix and the file secrets directory, rather than fixed literals, so the probe adapts to different deployments.
- **FR-010**: The system MUST tolerate a missing or unmounted secrets directory without failing.

### Key Entities *(include if feature involves data)*

- **Secret Probe Result**: The outcome of checking one secret. Attributes: name, source (environment or file), presence status, value representation (per the value-handling rule), read timestamp.
- **Secret Probe Summary**: The outcome of the list probe. Attributes: the collection of environment secret results, the collection of file secret results, a read timestamp.
- **Secret Source**: The origin of a secret. Two kinds: environment variable and mounted file.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A platform engineer can confirm whether a single injected secret reached the service in one probe call, with a clear present-or-missing result.
- **SC-002**: The probe distinguishes a present secret from a missing secret in 100% of cases, with no false "present" for an absent secret.
- **SC-003**: For every present secret, the probe returns the exact injected value, verified by comparing the response against the known injected content for each source.
- **SC-004**: A full secret-injection setup can be validated in a single list call, with every injected Pulse test secret accounted for.
- **SC-005**: The probe never fails the service when a secret, file, or secrets directory is absent; every such case returns a successful response with a "missing" status.

## Assumptions

- The probe is part of the synthetic test suite. It validates infrastructure delivery of secrets. It is not a secrets manager and does not persist secrets.
- Secrets are injected at deployment time by the platform. The probe only reads what the platform injected. It never writes secrets.
- Environment secrets under test share a common name prefix (the draft uses `PULSE_`). File secrets live under a single configured directory (the draft uses `/secrets/pulse-test-file`).
- Secret presence reflects the region and instance that serve the request. Secret injection is a per-deployment concern, not replicated entity state.
- The endpoint is stateless. It reads live process environment and the file system on each call. It uses no Akka entity, view, or workflow.
