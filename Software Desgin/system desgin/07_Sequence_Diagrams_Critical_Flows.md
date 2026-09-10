# StudyOS Phase 0 - Sequence Diagrams for Critical Flows

## 1. Flow 1 - Source Upload and Asynchronous Ingestion

![Source Ingestion Sequence](diagrams/seq_01_source_ingestion.png)

### Invariants
- file upload does not synchronously parse/embed;
- Core persists `QUEUED` + outbox atomically;
- every worker is idempotent;
- source becomes `READY` only after required stages succeed;
- illegal backward state transitions are ignored/rejected.

### Failure paths
- checksum/object mismatch -> fail before parse event;
- parser unsupported/corrupt -> `source.processing.failed.v1`, retryable=false;
- transient embedding error -> retry queue;
- duplicate parsed/indexed event -> no duplicate chunks because deterministic unique keys.

## 2. Flow 2 - Grounded AI Chat over WebSocket

![Grounded Chat Sequence](diagrams/seq_02_grounded_chat.png)

### Invariants
- browser socket terminates at Core;
- user message persisted before AI execution;
- FastAPI receives signed tenant context;
- retrieval is workspace/notebook scoped;
- citation IDs are validated against retrieved chunks;
- final assistant message is durable even though partial deltas are transient.

### Insufficient evidence behavior
If retrieval/context does not support a grounded answer, AI returns `INSUFFICIENT` or `PARTIAL`. The application should say evidence is insufficient rather than fabricate a citation.

## 3. Flow 3 - Quiz Generation, Attempt and Mastery Update

![Quiz and Mastery Sequence](diagrams/seq_03_quiz_mastery.png)

### Invariants
- quiz generation may be async, but quiz attempt/scoring is Core-owned;
- generated questions retain source/concept provenance;
- completing an attempt is idempotent;
- mastery changes only from explicit evidence;
- evidence history is append-only for auditability.

### Important transaction
Attempt finalization + evidence insertion + mastery projection update are one logical transactional unit. If asynchronous analytics publication fails, outbox preserves the event for later delivery.

## 4. Flow 4 - Flashcard Review and Spaced Scheduling

![Flashcard Review Sequence](diagrams/seq_04_flashcard_review.png)

### Invariants
- scheduler is deterministic and independent from LLM;
- user review history is private per user;
- the same review request cannot be applied twice;
- scheduler state and immutable review record change in one transaction;
- concept-linked recall creates mastery evidence with conservative weight.

### Concurrency
Lock/optimistically version the current card state to avoid two tabs grading the same due card and producing inconsistent schedules.

## 5. Flow 5 - YouTube Language Lab to Vocabulary Review

![Language Lab Sequence](diagrams/seq_05_youtube_language_lab.png)

### Invariants
- transcript acquisition is provider-abstracted and may legitimately be unavailable;
- timestamped transcript is stored as source-derived data;
- translation/grammar/vocabulary analysis is structured AI output;
- saved vocabulary preserves source sentence/timestamp provenance;
- "create flashcard" enters the same general Review module, not a parallel scheduler.

### Failure path
If transcript acquisition is unavailable, the source remains usable only for metadata/manual transcript fallback if product supports it. The system must not falsely claim a transcript was fetched.

## 6. Cross-Flow Traceability
Every critical flow carries:
- `traceId` across HTTP/internal HTTP/RabbitMQ/model calls;
- tenant/workspace scope where applicable;
- `requestId`/`eventId` idempotency identifiers;
- user-safe error code;
- audit or operational telemetry for state-changing events.

