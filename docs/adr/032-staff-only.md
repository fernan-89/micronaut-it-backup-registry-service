# ADR-032: The Backup Registry Is for IT Staff and Their Tools

## Status
Accepted

## Context
Policies name assets and how well they are protected; runs name when and why a backup failed. A requester (end user) has no business reading them.

## Decision
- Every route refuses a `REQUESTER` with 403 `ERR-BKP-00403`; any other role, or none, is staff (the same rule as the monitor, the alerting service and the incident service). The tools that report runs are service identities, which are not requesters.
- Every lookup is scoped by `X-Tenant-Id`: another tenant's policy or run is a 404, never a 403.
- **There is no delete.** A policy is paused (its history stays), a run is a fact.
- Audit is kept on the policy only, for real events (initiated, updated, paused, resumed), with the executor id. Runs are not audited: they are the record themselves, with `reportedBy`.
- **Nothing a tool says is kept as text**: no message, path or log line, only the fixed failure reason, times, size and the executor.

## Consequences
- Positive: one access rule across the operations services; no free text that could leak a secret.
- Negative: a failed run says why only in one of six words; the detail stays in the tool's own log.
