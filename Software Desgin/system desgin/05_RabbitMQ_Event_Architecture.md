# StudyOS Phase 0 - RabbitMQ Exchange, Queue & Event Design

## 1. Messaging Purpose
RabbitMQ is used for work that is long-running, bursty, provider-dependent or not required to complete inside the initiating HTTP transaction. It is not used merely to make the architecture look distributed.

Primary workloads:
- source parse/normalize/index/enrich;
- transcript acquisition;
- quiz/flashcard/study-guide generation;
- learning/product integration events;
- future notifications and analytics fan-out.

## 2. Delivery Model
Assume **at-least-once delivery**. Therefore:
- every consumer is idempotent;
- a message can be delivered more than once;
- publisher confirms are enabled;
- durable exchanges/queues are used for durable workflows;
- poison messages eventually reach DLQ;
- a business transaction that must publish uses transactional outbox.

Do not design around "exactly once" broker delivery.

## 3. Exchange Topology

| Exchange | Type | Role |
|---|---|---|
| `studyos.source.commands` | topic | requested source/transcript work |
| `studyos.source.events` | topic | facts emitted by source workers |
| `studyos.artifact.commands` | topic | quiz/flashcard/study-guide generation requests |
| `studyos.artifact.events` | topic | generated artifact facts |
| `studyos.learning.events` | topic | quiz/review/mastery facts |
| `studyos.retry` | topic | delayed retry routing when using TTL retry queues |
| `studyos.dlx` | topic | terminal dead-letter routing |

Commands use imperative/requested naming. Events use past tense/fact naming.

## 4. Queue Topology

| Queue | Binding | Consumer | Concurrency baseline |
|---|---|---|---:|
| `q.source.parse.v1` | `source.parse.requested.v1` | Parse worker | 2 |
| `q.source.index.v1` | `source.parsed.v1` | Index/embed worker | 2 |
| `q.source.enrich.v1` | `source.indexed.v1` | Concept enrichment worker | 1 |
| `q.source.transcript.v1` | `transcript.fetch.requested.v1` | Transcript worker | 1 |
| `q.core.source-state.v1` | `source.*.v1` selected success/failure facts | Core source event consumer | 2 |
| `q.artifact.quiz.v1` | `quiz.generate.requested.v1` | Artifact worker | 1-2 |
| `q.artifact.flashcards.v1` | `flashcards.generate.requested.v1` | Artifact worker | 1-2 |
| `q.core.artifact-state.v1` | `quiz.generated.v1`, future artifact events | Core artifact consumer | 1 |
| `q.analytics.learning.v1` | `quiz.attempt.completed.v1`, `flashcard.reviewed.v1`, `mastery.updated.v1` | Analytics projector | 1 |

The first implementation may run multiple consumer classes in one process. Queue separation still allows later independent deployment/scaling.

## 5. Event Envelope
Every durable message uses the same envelope:

```json
{
  "eventId": "uuid",
  "eventType": "source.parsed.v1",
  "eventVersion": 1,
  "occurredAt": "2026-09-10T14:00:00Z",
  "producer": "source-parse-worker",
  "traceId": "trace-id",
  "correlationId": "source-version-or-request-correlation",
  "causationId": "previous-event-id-or-null",
  "workspaceId": "uuid",
  "aggregateType": "SOURCE",
  "aggregateId": "uuid",
  "payload": {}
}
```

Rules:
- `eventId` is globally unique and immutable;
- `eventType` includes version;
- `traceId` propagates distributed trace;
- `correlationId` groups one business workflow;
- `causationId` points to the event/command that caused this message;
- never put secrets or full authentication tokens in payload;
- use IDs/object keys rather than very large source content.

Schema: `contracts/event-envelope.schema.json`.

## 6. Event Catalog

### Source pipeline
| Event | Producer | Consumer | Meaning |
|---|---|---|---|
| `source.parse.requested.v1` | Core outbox | parse worker | uploaded source is verified and may be parsed |
| `source.parsed.v1` | parse worker | index worker/Core projection | normalized representation is available |
| `source.indexed.v1` | index worker | enrichment worker/Core projection | chunks/embeddings are durable and queryable |
| `source.ready.v1` | enrichment worker/orchestrator | Core | required ingestion stages complete |
| `source.processing.failed.v1` | any source worker | Core | stage failed with retryability classification |
| `transcript.fetch.requested.v1` | Core outbox | transcript worker | acquire permitted transcript for a media source |
| `source.transcript.ready.v1` | transcript worker | Core/index path | timestamped transcript stored |

### Artifact pipeline
| Event | Producer | Consumer | Meaning |
|---|---|---|---|
| `quiz.generate.requested.v1` | Core outbox | artifact worker | generate grounded quiz for requested scope |
| `quiz.generated.v1` | artifact worker | Core | validated quiz persisted/available |
| `flashcards.generate.requested.v1` | Core outbox | artifact worker | generate grounded flashcards |
| future `flashcards.generated.v1` | artifact worker | Core | deck/cards available |

### Learning facts
| Event | Producer | Consumer | Meaning |
|---|---|---|---|
| `quiz.attempt.completed.v1` | Core Quiz | analytics/recommendation projection | immutable completed attempt fact |
| `flashcard.reviewed.v1` | Core Review | analytics/recommendation projection | user recall grade recorded |
| `mastery.updated.v1` | Core Learning | analytics/recommendation projection | materialized mastery changed due to evidence |

## 7. Transactional Outbox Pattern
For operations that change business state and must trigger async work:

```text
BEGIN DB TX
  change aggregate
  INSERT outbox_events(...)
COMMIT
```

A separate publisher loop:
1. selects unpublished events in small batches;
2. locks rows using `FOR UPDATE SKIP LOCKED` or equivalent;
3. publishes with publisher confirms;
4. sets `published_at` after broker confirm;
5. retries failures without altering business state.

This prevents the dual-write failure where database commits but broker publish is lost.

## 8. Consumer Idempotency Pattern
Consumer pseudocode:

```text
receive(event)
BEGIN TX
  if processed_events contains (consumerName,eventId):
      ACK and return

  validate schema/version
  perform deterministic mutation using unique business keys
  insert processed_events(consumerName,eventId)
COMMIT
ACK
```

If the process dies before commit, redelivery is safe. If it dies after commit but before ACK, `processed_events` makes redelivery a no-op.

For large AI derived writes, use deterministic unique keys such as `(source_version_id, chunk_key)` in addition to event ID deduplication.

## 9. Retry Strategy
Classify errors:

### Retryable
- network timeout;
- provider `429`/temporary `5xx`;
- transient object storage/database connectivity;
- temporary model capacity.

### Non-retryable
- unsupported/corrupted file;
- authorization/business invalid state;
- invalid message schema/version;
- permanent provider access denial.

Recommended retry schedule for provider-bound jobs:
```text
attempt 1: immediate worker handling
retry 1: 10 seconds
retry 2: 60 seconds
retry 3: 5 minutes
retry 4: 30 minutes
then DLQ / manual retry depending on error
```

Exact values become environment configuration.

## 10. Retry Queue Pattern
One practical RabbitMQ design:

```text
main queue
  failure -> publish/route to q.<name>.retry.10s
retry queue (x-message-ttl=10s, DLX back to main exchange/routing key)
  failure -> progressively longer retry queue
terminal -> studyos.dlx -> q.<name>.dead.v1
```

Alternative broker plugins/delayed exchange can be adopted later, but the application contract should not depend on them.

## 11. Dead Letter Queue Operations
Every durable worker class has a DLQ or a clear shared DLQ with original routing metadata.

DLQ alert includes:
- queue/routing key;
- event ID;
- source/job ID;
- error classification;
- attempt count;
- trace ID.

Never automatically replay an entire DLQ without confirming the root cause and idempotency behavior.

## 12. Prefetch and Backpressure
Set consumer prefetch by workload:
- CPU-heavy parse: low prefetch, often 1-2 per worker;
- external model calls: bounded concurrency based on provider quota;
- lightweight state projection: higher prefetch.

Do not allow unlimited concurrent LLM/embed requests just because the queue is deep. Worker concurrency is a cost and provider-protection mechanism.

## 13. Message Size and Payload Rules
- Target message payload below ~256 KB; normally much smaller.
- Store source content and generated large artifacts in database/object storage; send references.
- Do not send vector arrays through RabbitMQ unless there is a measured reason.
- Compressing huge messages is not a substitute for correct object storage design.

## 14. Ordering
StudyOS does not assume global event ordering. When order matters for one aggregate:
- include aggregate ID and version/stage;
- reject illegal backward state transitions;
- design handlers to tolerate stale duplicate events;
- use optimistic state/version checks.

Example: a late `source.parsed.v1` must not move a source from `READY` back to `NORMALIZED`.

## 15. Event Versioning
Never change the meaning of `*.v1` in place after consumers depend on it.

Compatible additions may add optional fields. Breaking payload/semantic changes create `*.v2` and run both versions during migration when needed.

## 16. Security
- RabbitMQ is private network only in production.
- TLS for broker connections where infrastructure supports it.
- separate users/credentials per service role;
- workers receive only permissions for required exchanges/queues;
- payload logging redacts sensitive fields;
- event consumer never trusts role/authorization claims in message to bypass server-owned data checks.

## 17. Observability
Metrics per queue/consumer:
- ready/unacked messages;
- oldest message age;
- throughput;
- success/failure/retry count;
- processing duration;
- DLQ count;
- provider error category;
- per-job AI token/cost where relevant.

Trace propagation continues through `traceId`, `correlationId` and broker instrumentation.

## 18. Machine-Readable Contract
`contracts/asyncapi-rabbitmq.yaml` contains the baseline exchange/channel/message schemas. Treat it as version-controlled code. CI must parse it and run representative event schema tests.

