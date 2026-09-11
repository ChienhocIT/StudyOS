# Implementation review

Latest backend maintenance review: [12/09/2026 findings and fixes](CORE_REVIEW_2026-09-12.md).

Baseline: `2345ef0`. Scope: Phase 0 design, initial implementation commit `6d03440`,
and subsequent integration fixes. Review was delegated independently for standards
and specification, followed by targeted re-review of backend correctness.

## Standards

| Finding | Resolution | Evidence |
|---|---|---|
| Learning events had no durable consumer and could block outbox delivery | Added learning audit queue, consumer deduplication, per-event retry backoff in V5 | Failed-publish integration regression; real ingestion after quiz/review |
| Finalized upload could be reopened with an old idempotency key | Only uploadable state can issue upload URL; staging is copied to a verified private object | Core integration rejects reopening and checks download authorization |
| Long conversation history stopped at the first 1,000 messages | Latest-history query and backwards cursor pagination | Core regression with more than 1,000 messages |
| Architecture checks reported zero tests | Converted checks to explicit Jupiter tests and added cross-module adapter boundary | Four architecture tests executed successfully |

## Specification

| Finding | Resolution | Evidence / remaining limit |
|---|---|---|
| No shared AI quota or durable usage | Transactional user lock, durable reservations, daily plan and minute limits; completion records available telemetry | Chat/artifact/language hooks tested; actual spend hard cap and embedding accounting still pending |
| Deleted sources left active learning artifacts | Jobs invalidated, quizzes/decks become STALE; review queue excludes them | Integration test and real smoke deletion check |
| Uploaded citations had no authorized download | Added short-lived download URL after tenant/readiness authorization | Core negative tests; real browser upload/download still needs dedicated coverage |
| Provider streaming buffered whole answers | Incremental provider streaming, cancellation cleanup and Core response-body deadline | Python cancellation regressions and stalled HTTP server test |

## Follow-up fixes discovered during integration

- CORS bean name prevented Spring Security from applying the configured policy.
  Renamed it and tested allowed/rejected origins. Browser registration now passes.
- Several artifact validator branches did not throw their constructed exception.
  Invalid quiz types/answers/empty provenance now fail before persistence.
- Study guides lacked structured references. Python resolves references from actual
  evidence; Core checks current tenant/source scope and citation markers.
- Raw Markdown headings caused conservative concept extraction to return no candidates.
  Explicit headings and definition lines are now recognized; the real quiz-to-mastery
  smoke test passes without synthetic concept fixtures.
- Stale chat cleanup now terminates its usage reservation together with generation state.
- Upload staging lifecycle expires temporary objects after one day in local MinIO.
- Java formatting is enforced by Spotless in Maven verify. Python Docker builds now
  install their checked-in lockfiles. All four images built and the 10-group smoke
  and browser workflow passed against the full Compose stack.

## Remaining review scope

Passing local tests does not close the whole original backlog. Production IAM/database
role isolation, OAuth, provider-quality evaluation, monetary budget enforcement,
deployment/recovery drills, load tests and full multi-browser accessibility coverage
remain to be implemented or verified. No production deployment or remote CI result is claimed.
