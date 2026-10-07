# ADR-030: The Tool Reports, the Registry Keeps the Facts

## Status
Accepted

## Context
Backups are made by tools (a backup agent, a cron job, a cloud service) that the registry does not control. The registry has to say whether the promise made about an asset holds, without ever holding a credential, reaching an asset or running anything.

## Decision
- **The registry only records.** A `BackupPolicy` is the promise (how often, how much may be lost, how long a recovery may take, how long copies are kept, optionally how often a restore must be proved). A `BackupRun` is what a tool reports when a backup or a restore test ends (`run/initiate`). Nothing here schedules, starts or reads a backup.
- **A run is a fact**: immutable, never edited, never deleted. A failed run carries one reason from a fixed list (`TIMEOUT`, `STORAGE_FULL`, `SOURCE_UNREACHABLE`, `AUTH_FAILED`, `CHECKSUM_MISMATCH`, `OTHER`), never the tool's own words, which could hold paths or credentials. A successful run has none.
- **Reporting is idempotent**: the same policy, kind and start is the same run (unique index on `(organisationId, policyId, kind, startedAt)`, times cut to the millisecond). A repeat, or twenty at once, answers the stored run with 200 instead of 201, and no sovereign id is spent for it.
- **The clock of the tool may be a little off**: a run may finish at most 5 minutes after the registry's clock, never before it started.
- **The policy keeps a summary** of its runs: last run, last successful backup, last successful restore test and how long it took. Each part only moves forward in time (`$max`, and a compare on the restore test), so late or repeated reports change nothing they should not. A report that stored the run and died before the summary was updated is repaired by the tool's retry, because the summary is folded again even for a run already stored.

## Consequences
- Positive: no credentials, no access to assets, nothing to secure on the target side; any tool that can call an HTTP route can report.
- Negative: the registry trusts what the tool says. A tool that stops reporting is seen as a breach of the RPO (ADR-031), which is the point, but a tool that lies cannot be caught here.
