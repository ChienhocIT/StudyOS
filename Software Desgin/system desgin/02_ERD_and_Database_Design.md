# StudyOS Phase 0 - ERD & Database Design

## 1. Database Design Goals
The schema supports four connected concerns without mixing their ownership:

1. **Identity and tenancy** - users, workspaces, membership and sessions.
2. **Knowledge ingestion** - sources, versions, normalized sections, chunks, transcripts and vector/FTS indexes.
3. **Learning state** - concepts, relations, mastery evidence, goals, recommendations and study sessions.
4. **Learning activity and AI operations** - conversations, citations, quizzes, flashcards, vocabulary, AI run telemetry, audit and outbox.

The relational schema is the primary durable model. JSONB is used for versioned or heterogeneous metadata, but not to avoid modeling core relationships.

## 2. Full ERD

![Full ERD](diagrams/erd_full.png)

The zoomable SVG source is included in `diagrams/erd_full.svg`. Complete DDL is included in `database/schema.sql`.

## 3. Domain ERD - Identity & Content

![Identity and Content ERD](diagrams/erd_identity_content.png)

### 3.1 Identity and Tenancy Tables

| Table | Purpose | Important constraints |
|---|---|---|
| `users` | Primary user profile and account status | unique email; soft/deletion workflow at application layer |
| `user_identities` | OAuth/OIDC external identities | unique `(provider, provider_subject)` |
| `refresh_tokens` | Rotatable refresh sessions | store token hash only; revocable/expiring |
| `workspaces` | Tenant boundary | owner must be a user |
| `workspace_members` | Membership and RBAC | composite PK `(workspace_id, user_id)` |
| `notebooks` | Knowledge/learning workspace inside tenant | always belongs to one workspace |

### 3.2 Source and Derived Content Tables

| Table | Purpose | Important constraints |
|---|---|---|
| `sources` | Logical source shown to user | contains workspace + notebook scope and current state |
| `source_versions` | Immutable version of uploaded/fetched source | unique `(source_id, version_no)`; checksum supports duplicate control |
| `source_processing_jobs` | Persisted stage attempt/error metadata | unique `(source_version_id, stage)` |
| `document_sections` | Hierarchical normalized source structure | optional parent section; page/time boundaries |
| `document_chunks` | Retrieval unit | mandatory tenant scope; unique deterministic chunk key per version |
| `transcript_segments` | Timestamped transcript | ordered and bounded by start/end time |

## 4. Domain ERD - Learning State

![Learning ERD](diagrams/erd_learning.png)

### 4.1 Concept Model
`concepts` is notebook-scoped. The same text label in different notebooks is not automatically considered the same concept. This avoids premature global ontology design.

`concept_relations` supports at least:
- `PREREQUISITE`;
- `PART_OF`;
- `RELATED_TO`;
- `CONTRASTS_WITH`.

`chunk_concepts` retains provenance from concept back to supporting chunks.

### 4.2 Mastery Model
`user_concept_mastery` is a materialized current state. It must be reproducible/explainable from `mastery_evidence` history.

Mastery rules:
- reading/viewing alone produces zero or very low-weight evidence;
- quiz, delayed recall and evaluated explanation produce stronger evidence;
- score and confidence are separate;
- AI explanation does not directly increase mastery;
- concurrent mastery updates use optimistic locking via `version` or transaction-level row lock.

## 5. Domain ERD - Practice & Review

![Practice ERD](diagrams/erd_practice.png)

### 5.1 Artifact Lifecycle
`artifact_jobs` is the async job aggregate. A quiz or flashcard deck may reference the job that created it, but generated content remains durable after job completion.

### 5.2 Quiz Tables
- `quizzes` - quiz aggregate metadata;
- `quiz_questions` - question content and answer rubric;
- `quiz_question_concepts` - concept coverage;
- `quiz_attempts` - one user attempt;
- `quiz_answers` - saved answer per question.

Completing an attempt is a transactionally meaningful action: validate status -> calculate score -> finalize answers -> generate mastery evidence -> update mastery state -> publish completion event.

### 5.3 Flashcard Tables
- `flashcard_decks` - grouping/ownership;
- `flashcards` - card content, provenance and current scheduler state;
- `flashcard_reviews` - immutable review history.

The scheduler is deterministic. The review table stores previous/new state for audit and algorithm migration.

### 5.4 Language Vocabulary
`vocabulary_items` retains original context and optional transcript segment/source linkage. Creating a flashcard from vocabulary creates a normal reviewable card instead of a separate scheduler implementation.

## 6. Domain ERD - Conversation & Operations

![Conversation and Operations ERD](diagrams/erd_conversation_ops.png)

### 6.1 Conversation and Citation
Messages are ordered by `(conversation_id, sequence_no)`. `request_id` is unique within a conversation and supports retry/dedup for WebSocket commands.

A citation references a durable `document_chunk`. User-facing metadata is materialized from chunk/source relationships when serving the response. The model never owns source metadata.

### 6.2 AI Operations
`ai_runs` records model/provider usage and performance. It is not used as a source of business truth. Keep raw sensitive prompt content out of this table; store IDs, metrics, config versions and safe evaluation metadata.

### 6.3 Transactional Outbox
`outbox_events` is written in the same database transaction as a business mutation. A publisher claims unpublished rows, publishes to RabbitMQ, then records `published_at`. `processed_events` supports consumer-level deduplication.

## 7. Full Table Catalog

### Identity & Tenancy
- `users`
- `user_identities`
- `refresh_tokens`
- `workspaces`
- `workspace_members`

### Knowledge Workspace & Ingestion
- `notebooks`
- `sources`
- `source_versions`
- `source_processing_jobs`
- `document_sections`
- `document_chunks`
- `transcript_segments`

### Concept & Learning State
- `concepts`
- `concept_relations`
- `chunk_concepts`
- `user_concept_mastery`
- `mastery_evidence`
- `learning_goals`
- `learning_recommendations`
- `study_sessions`

### Conversation & Knowledge Capture
- `conversations`
- `messages`
- `citations`
- `message_feedback`
- `notes`
- `note_citations`

### Active Learning
- `artifact_jobs`
- `quizzes`
- `quiz_questions`
- `quiz_question_concepts`
- `quiz_attempts`
- `quiz_answers`
- `flashcard_decks`
- `flashcards`
- `flashcard_reviews`
- `vocabulary_items`

### Operations
- `ai_runs`
- `audit_logs`
- `outbox_events`
- `processed_events`

## 8. Multi-Tenancy Strategy
Every high-risk retrieval/business table carries or can deterministically resolve a workspace. For retrieval performance and safety, `document_chunks` explicitly stores both `workspace_id` and `notebook_id` even though those can be derived from the source chain.

Mandatory rule: a repository method that returns tenant-owned data must take `WorkspaceId`/tenant context, or use a repository abstraction that injects it automatically. Do not expose generic `findById(UUID)` for tenant-owned aggregates without a second authorization step.

Examples:
- safe: `findNotebook(workspaceId, notebookId)`;
- safe: `searchChunks(workspaceId, notebookId, sourceIds, queryEmbedding)`;
- unsafe: `findChunk(chunkId)` used directly from user input.

## 9. Retrieval Index Design

### 9.1 Full Text
`document_chunks.search_vector` is a generated `tsvector` with GIN index. Phase 0 uses the `simple` configuration to avoid language-specific stemming surprises. Language-specific tuning is an evaluation task, not an initial assumption.

### 9.2 Vector
`document_chunks.embedding` uses `vector(1536)` in the provided baseline DDL. This is an implementation placeholder tied to the first selected embedding model. If the model dimension changes, migrate explicitly; do not silently mix vectors from incompatible models.

Use HNSW cosine index after enough data exists to justify approximate search. Exact search can be retained for small evaluation datasets.

### 9.3 Scope/Lookup Indexes
Required composite indexes include:
- `sources(notebook_id, status, updated_at)`;
- `document_chunks(workspace_id, notebook_id, source_version_id)`;
- `messages(conversation_id, sequence_no)`;
- `mastery_evidence(user_id, concept_id, occurred_at)`;
- `learning_recommendations(user_id, status, priority_score)`;
- `flashcard_reviews(user_id, reviewed_at)`;
- unpublished `outbox_events` partial index.

## 10. Transaction Boundaries

### T1 Create source + queue work
One DB transaction:
1. create/update source/version state;
2. insert processing job metadata if applicable;
3. insert outbox event.

Object upload itself is not in the database transaction. Completion is verified separately before queueing.

### T2 Complete quiz
One DB transaction:
1. lock/validate attempt is `IN_PROGRESS`;
2. calculate score from persisted answers;
3. set attempt `COMPLETED`;
4. insert evidence records;
5. update mastery rows with optimistic version/lock;
6. insert outbox event.

### T3 Grade flashcard review
One DB transaction:
1. lock card scheduler state;
2. compute deterministic next state;
3. append review record;
4. update card due/scheduler state;
5. append mastery evidence if concept-linked;
6. update mastery state;
7. outbox learning event.

### T4 Persist assistant final result
One DB transaction after stream completes:
1. finalize assistant message content/status;
2. validate and persist citations;
3. persist usage reference/summary;
4. emit product telemetry/outbox if required.

Partial streaming tokens are not required to be individually durable.

## 11. Idempotency Rules
- Source version uniqueness uses source + checksum/version controls.
- Chunks use deterministic `chunk_key` unique per source version.
- `processed_events(consumer_name,event_id)` rejects duplicate consumer handling.
- Artifact generation accepts `idempotency_key` per user/job request.
- WebSocket user commands use `requestId`; duplicate sends return/resume the same logical message.
- Quiz attempt completion is state-checked and safe to retry.
- Flashcard review POST should accept a request/idempotency key in the implementation even if the baseline REST example keeps the body minimal.

## 12. Delete and Retention Workflow
Deleting a source must eventually remove:
- original/normalized object storage objects;
- source versions/sections/chunks/embeddings/transcript segments;
- dependent chunk-concept edges;
- citations referencing deleted source according to product retention policy;
- generated artifacts whose source dependency becomes invalid, or mark them stale.

User/account deletion requires a separate orchestration workflow because some records may need anonymized operational retention. The exact retention period is a policy decision outside Phase 0.

## 13. Migration Strategy
Use Flyway or Liquibase in Core API as the owner of relational schema migrations. If AI workers need schema additions, they are still merged through one migration pipeline to avoid competing owners.

Rules:
- migrations are immutable after release;
- use expand/contract for breaking changes;
- deploy backward-compatible schema before code depending on it;
- add indexes concurrently in production where appropriate;
- never run destructive migrations automatically without backup/rollback plan.

## 14. Database Deliverables
- `database/schema.sql` - PostgreSQL baseline with PK/FK, constraints and indexes.
- `database/studyos.dbml` - modeling/import source.
- `diagrams/erd_full.svg` - complete zoomable relationship diagram.
- Four domain-specific ERD views for easier review.

