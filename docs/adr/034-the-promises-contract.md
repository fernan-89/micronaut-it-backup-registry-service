# ADR-034: The Promises Contract

## Status
Accepted. Implements platform ADR-001 (`thinklab-platform/docs/adr/001-the-promises-contract.md`).

## Context
The alerting service used to know this registry's fields and verdicts (which protection or restore-test state is a broken promise, and its code). A rule that lived in a consumer was untested with the domain and had to be kept in step by hand.

## Decision
- The registry answers `GET /it-backup-registry/v1/promises/retrieve?changedWithinDays=&limit=` as the platform contract says: every broken promise and every one that changed within the window (30 days unless asked, 1 to 365), at most `limit` of each (500 unless asked, 1 to 1000), broken first.
- **The judgement lives here:** a policy is **broken** when its latest backup is outside the RPO (`BACKUP_RPO_BREACHED`) or its restore test is overdue (`BACKUP_RESTORE_TEST_OVERDUE`), **kept** when there is a backup inside the RPO, and **not judged** when it is paused or has never been backed up yet (still inside its first RPO). The name is the policy's name, the asset is the policy's asset, the status is the policy's (`ACTIVE` or `PAUSED`).
- **`changedAt`** is the latest of the policy's last update, run, successful run and restore test: whatever can recover or break the promise.
- Staff only, tenant-scoped, read-only (ADR-032).

## Consequences
- Positive: the rule is judged and tested with the domain; the consumer needs no knowledge of backup.
- Negative: a policy that stops being broken only by time passing would not be listed unless it changed (none: leaving the RPO breach needs a new successful backup, which moves `changedAt`).
