# micronaut-it-backup-registry-service

BIAN-aligned Service Domain **it-backup-registry** (Control Record: `BackupPolicy`), port `8105`.

The third slice of Journey 14 (operations): a **registry of backups**. A `BackupPolicy` is the promise made about one asset (how often it is
backed up, how much data may be lost, how long a recovery may take, how long copies are kept, how often a restore must be proved). A
`BackupRun` is what a tool reports when a backup or a restore test ends. The registry says, **when you ask**, whether each promise holds.

## What it guarantees, and what it does not

- **It only records** (ADR-030): the tool that makes the backup reports each run (`run/initiate`). The registry never starts, schedules or
  reads a backup, holds no credential and reaches no asset.
- **A run is a fact** (ADR-030): never edited, never deleted. A failed run carries one reason from a fixed list (`TIMEOUT`, `STORAGE_FULL`,
  `SOURCE_UNREACHABLE`, `AUTH_FAILED`, `CHECKSUM_MISMATCH`, `OTHER`), never the tool's own text. A run may finish at most 5 minutes
  ahead of the registry's clock.
- **Reporting is idempotent** (ADR-030, ADR-033): the same policy, kind and start is the same run. A repeat, or 20 at once, answers the stored
  run with 200 (a new one is 201); the tool may retry as often as it likes.
- **The promise is judged when read** (ADR-031), never stored: `protection` is `OK` (a backup inside the RPO), `NEVER` (none yet, still inside
  the RPO of the policy's creation), `RPO_BREACHED`, or `PAUSED` (not judged). `restoreTest` is `OK`, `OVERDUE` or `NOT_REQUIRED`; `rtoMet`
  compares the last successful restore test with the recovery time objective and is `null` until one was done.
- **A policy cannot promise what it cannot do** (ADR-031): the RPO cannot be shorter than the backup frequency.
- **Staff only** (ADR-032): every route refuses a `REQUESTER` with 403 `ERR-BKP-00403`; another tenant's policy or run answers 404.
  No free text from a tool is kept, and there is no delete (a policy is paused).
- **Safe with several instances** (ADR-033): a policy name is unique per organisation, one run per `(policy, kind, start)` is a unique
  index, every change is guarded by the status loaded, and the summary of the runs only moves forward in time whatever the order of the reports.
- **Not here yet:** telling someone when a policy breaches its RPO (a later pairing with alerting), executing or scheduling backups, patches
  (the next slice), storage capacity, per-asset discovery of what is unprotected.

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call; `X-Executor` is mandatory on the actions (for a run, the name of the tool); `X-Role` is optional
and, with platform security on, comes from the verified token.

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-backup-registry/v1/initiate` `{"name":"Database nightly","assetId":"<uuid>","frequencyHours":24,"rpoHours":48,"rtoMinutes":120,"retentionDays":30,"restoreTestEveryDays":30}` (`restoreTestEveryDays` is optional) |
| retrieve | `GET /it-backup-registry/v1/{id}/retrieve` (judged now) |
| promises/retrieve | `GET /it-backup-registry/v1/promises/retrieve?changedWithinDays=&limit=` (the promises contract, ADR-034: every broken promise and every one that changed lately, each with `verdict`, a fixed `code` when broken, `status` and `changedAt`) |
| retrieve (collection) | `GET /it-backup-registry/v1/retrieve?status=&assetId=&protection=` (oldest first) |
| update | `PUT /it-backup-registry/v1/{id}/update` (the whole definition again, in any status) |
| control/pause | `PUT /it-backup-registry/v1/{id}/control/pause` (ACTIVE -> PAUSED: no longer judged) |
| control/resume | `PUT /it-backup-registry/v1/{id}/control/resume` (PAUSED -> ACTIVE) |
| audit-log/retrieve | `GET /it-backup-registry/v1/{id}/audit-log/retrieve` |
| run/initiate | `POST /it-backup-registry/v1/run/initiate` `{"policyId":"<uuid>","kind":"BACKUP","outcome":"SUCCEEDED","startedAt":"<instant>","finishedAt":"<instant>","sizeBytes":1048576}` (`sizeBytes` optional; `failureReason` only with `FAILED`, and then mandatory) |
| run/retrieve | `GET /it-backup-registry/v1/run/{id}/retrieve` |
| run/retrieve (collection) | `GET /it-backup-registry/v1/run/retrieve?policyId=&kind=&outcome=&limit=` (newest start first, 50 unless asked, at most 200) |

```text
policy: ACTIVE <-> PAUSED
run:    a fact (BACKUP or RESTORE_TEST; SUCCEEDED or FAILED), never changes
```

```bash
curl -X POST "http://localhost:8105/it-backup-registry/v1/run/initiate" -H "X-Tenant-Id: <organisationId>" -H "X-Executor: backup-agent-1" \
  -H "Content-Type: application/json" \
  -d '{"policyId":"<policyId>","kind":"BACKUP","outcome":"SUCCEEDED","startedAt":"2026-10-07T02:00:00Z","finishedAt":"2026-10-07T02:14:00Z","sizeBytes":1048576}'
```

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-BKP-00403` | 403 | A REQUESTER used the backup registry (ADR-032) |
| `ERR-BKP-00404` | 404 | Policy or run not found (another tenant's answers the same) |
| `ERR-BKP-00409` | 409 | Duplicate policy name, illegal transition (pause a paused policy), or it changed while the write was applied (retry) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure, an RPO shorter than the frequency, a run that finishes before it starts or in the future, a failed run without a reason |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Configuration

`HASH_SERVICE_URL` (default `http://localhost:8080`, the sovereign ids), `MICRONAUT_SERVER_PORT` (default 8105), `mongodb.uri`,
`thinklab.mongo.create-indexes` (default on). The service calls no other service and has no scheduler.

Do not put personal data in a policy name: it is stored with the policy.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 the tool reports, the registry keeps the facts · 031 the promise is judged when read · 032 staff only · 033 guarded writes and unique backstops · 034 the promises contract.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
