# ADR-033: Guarded Writes, and Unique Indexes as the Atomic Backstops

## Status
Accepted

## Context
Several instances serve the registry, staff edit policies while tools report runs, and tools retry. Checking first and writing later is not atomic.

## Decision
- Every change to an existing policy is **one atomic update guarded by the status that was loaded**, which also appends the audit entry. A lost race is 409 (`ERR-BKP-00409`): read again and decide again.
- **A policy name is unique per organisation** (`(organisationId, name)`), on create and on rename; the index is the atomic backstop of a duplicate.
- **One run per `(organisationId, policyId, kind, startedAt)`** is a unique index. The loser of a simultaneous insert reads the stored run and answers it as not created.
- The summary of the runs is updated with `$max`; the restore test is replaced only when the stored one is empty or older, in a second guarded update. Order and repetition of reports do not matter.
- Indexes are created at startup, fail-open and idempotent; `thinklab.mongo.create-indexes=false` turns them off. A third index `(organisationId, policyId, startedAt desc)` serves the real run query.
- The mongo adapters set the update time themselves and never write what the domain did not ask for.

## Consequences
- Positive: with N instances and a retrying tool there is still exactly one run per start and no lost updates.
- Negative: without the index (it is fail-open) a simultaneous double report could store two runs; the startup log says when an index could not be created. The summary and the run are two writes: a crash between them is repaired by the retry (ADR-030).
