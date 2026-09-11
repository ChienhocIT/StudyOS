# Core API maintenance review — 12/09/2026

Review baseline: implementation `793815c`, checked against original design baseline
`2345ef0` and the system-design pack. Independent Standards and Spec agents reviewed
the backend; implementation and verification followed on the shared branch.

## Standards

| Finding | Fix | Regression evidence |
|---|---|---|
| P1: concurrent PATCH could resurrect a deleted notebook | Conditional update excludes DELETED and checks affected rows | Stale read → delete → update remains DELETED |
| P1: container deletion left sources and private objects behind | `SourceScopeCleanup` application port with source-owned JDBC adapter; outbox, state and artifact invalidation in caller transaction | Notebook/workspace cleanup tests, including archived children |
| P1: concurrent creation could commit after cleanup scanned children | V7 database guards take parent locks and reject inactive/mismatched scope | Real two-connection PostgreSQL blocking test, SQLSTATE 23514 |
| P2: nullable DataSource access in integration setup | Inject `DataSource` directly | Outbox integration test still executes |

The cleanup seam avoids a circular dependency between NotebookService and SourceService.
Applications depend on application ports; persistence remains in adapters. All four
architecture checks pass. POM and Java formatting were normalized.

## Spec

| Finding | Fix | Regression evidence |
|---|---|---|
| P1: late facts from an earlier source attempt could overwrite a retry | New source_version per retry; current-version fence rejects old facts | Old parsed/ready/failed facts cannot affect a fresh attempt |
| P2: retry/delete repeats could enqueue duplicate work or return inconsistent results | Durable optional retry Idempotency-Key; repeated DELETING/DELETED delete is a no-op after authorization | Retry replay, new-key retry, unauthorized replay and repeated delete tests |
| P1: an open socket could receive Redis deltas after scope revocation | Check scope for active generation, completion, Redis push and replay; cancel generation/reservation and close revoked socket | Five authorization/delivery regressions |
| P2: archived/revoked artifact jobs could remain pending when results arrived | Locate job in original workspace, then terminate unavailable scope and its reservation | Archived notebook result arrival ends FAILED without saving quiz |
| P2: invalid learning-goal changes caused 500/truncation | Validate the whole change set; exact integer minutes, strict dates/status/description, nullable clears | Real HTTP invalid/clear/atomicity checks |
| P2: stale decks and withdrawn concepts remained actionable | Filter current READY evidence and decks; align analytics due count with review queue | Deletion removes recommendations and due count |
| P2: malformed optional provider telemetry could roll back valid results | Preserve unavailable measurements as null; bound trace/model fields | Completion survives invalid count/cost values without inventing zero usage |

Source retry clients should reuse an Idempotency-Key for network retries. Legacy
keyless requests still work for a FAILED retryable source, but in-flight keyless
duplicates return 409; they are not treated as a new operation.

## Verification

- Core: 57 tests pass, including PostgreSQL Testcontainers and four architecture rules.
- Targeted LearningIntegrationTest re-run after analytics adjustment: 9 pass.
- Worker: 43 tests pass, including seven scope/cleanup regressions.
- Core/worker Docker images rebuilt successfully. Migrations V6/V7 are additive;
  original V1–V5 and public design contracts remain unchanged.
- Real Docker cleanup smoke passed for notebook deletion and workspace deletion with
  an archived child: source reaches DELETED, original/normalized MinIO objects return
  404, and derived chunks are removed.
- The existing 10-group learning smoke passed again against the updated containers.

## Practical limits

Scope is rechecked per stream frame; measure database load before increasing production
traffic. Permission changes and frame transmission are not one distributed transaction.
Deletion is asynchronous: the API commits DELETING plus a durable command, and worker
completion changes it to DELETED. Cited historical chunks are redacted by the existing
retention policy; completed learning evidence is retained.

This review does not certify all production requirements. Provider quality, monetary
limits, OAuth, least-privilege infrastructure, fault injection and load/recovery tests
remain on the original backlog. Malformed or missing worker deliveries still require
operational DLQ monitoring and recovery.
