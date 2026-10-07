# ADR-031: The Promise Is Judged When Read

## Status
Accepted

## Context
Whether a policy is met depends on the clock, not on any event: a backup that does not arrive is not a message. Storing a verdict would need a job to keep it true.

## Decision
- **Nothing about the verdict is stored.** `protection`, `protectedUntil`, `restoreTest`, `restoreTestDueAt` and `rtoMet` are computed from the policy and its summary at the moment of each read, like the SLA of the incident service (incident ADR-032).
- **Protection**: the recovery point is the last successful backup, or the creation of the policy while there is none yet. Data is protected until that plus `rpoHours`. `OK` has a backup inside the RPO, `NEVER` has none yet but is still inside the RPO of the creation, `RPO_BREACHED` is past it, `PAUSED` is not judged.
- **Restore test**: when `restoreTestEveryDays` is set, a test is due that many days after the last successful one, or after the creation. `OK` or `OVERDUE`; `NOT_REQUIRED` when it is left out or the policy is paused.
- **RTO**: the duration of the last successful restore test (whole minutes, rounded up, at least one) against `rtoMinutes`. `rtoMet` is unknown (null) until a restore was tested, because a backup that was never restored proves nothing about the recovery time.
- **A policy cannot promise what it cannot do**: the RPO may not be shorter than the backup frequency, and every number has a ceiling (hours up to a year, retention up to ten years, RTO up to 30 days).
- The list is filterable by status, asset and protection; the protection filter runs in memory over the tenant's policies because it is judged when read.

## Consequences
- Positive: always true at the moment of reading, nothing to repair after a restart or a clock change, no scheduler.
- Negative: the list reads every policy of the tenant and judges it (cheap for the expected size, a limit for very large tenants). Telling someone that a breach happened needs a reader: pairing this with the alerting service is a later slice.
