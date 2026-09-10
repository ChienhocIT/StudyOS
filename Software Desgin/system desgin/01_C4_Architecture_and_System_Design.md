# StudyOS Phase 0 - C4 Architecture & System Design

## 1. Purpose and Scope
This document freezes the implementation baseline for StudyOS before coding. It translates the product blueprint into deployable boundaries, data ownership rules, runtime flows and scaling decisions. The target is an MVP that one developer or a small team can operate, while still exposing real backend, AI engineering and production concerns.

The architecture is deliberately **not** microservice-first. StudyOS starts as a Spring Boot modular monolith for transactional business capabilities, a separately deployable FastAPI AI service, and independent asynchronous Python workers. Services are split further only when measured operational evidence justifies the cost.

## 2. Architecture Quality Attributes
The design prioritizes these attributes in this order:

1. **Tenant isolation and authorization correctness.** No retrieval, source, conversation, quiz, note or learning state may cross workspace boundaries.
2. **Grounded AI traceability.** A grounded answer must be traceable to retrieved chunks and original source page/timestamp.
3. **Data integrity.** Business state transitions, quiz completion, review grading and mastery evidence must be transactionally safe.
4. **Idempotent asynchronous processing.** Source-processing events may be delivered more than once without duplicate chunks or artifacts.
5. **Operability.** HTTP, WebSocket, RabbitMQ and AI calls share trace IDs and are observable.
6. **Progressive scalability.** Early architecture should handle beta-to-small-production traffic without premature platform complexity.
7. **Cost control.** AI usage, token consumption and expensive background jobs are metered per feature and user/workspace.

## 3. Planning Assumptions
These are design assumptions, not product promises. Revisit them after real telemetry exists.

| Dimension | Phase 0 planning baseline |
|---|---|
| Beta users | 5-50 invited users |
| Small-production design envelope | up to ~5,000 MAU before major architectural split |
| Concurrent WebSocket sessions | 50-200 typical small-production target |
| Source file size | default max 100 MB; per-plan configurable |
| PDF size | target support up to ~500 pages with async processing |
| Active AI generations per user | 1 per conversation; small global concurrency limit per plan |
| Delivery semantics | RabbitMQ at-least-once |
| Persistent source of truth | PostgreSQL |
| Vector store | pgvector in same PostgreSQL cluster initially |
| Object storage | S3 production, MinIO local |
| Container orchestration | Docker Compose local; ECS/Fargate or EC2 production before Kubernetes |

## 4. C4 Level 1 - System Context

![C4 Context](diagrams/c4_context.png)

### 4.1 Actors and External Systems
- **Learner:** creates notebooks, uploads/links sources, asks questions, completes quizzes, reviews flashcards, saves vocabulary and follows recommendations.
- **StudyOS Operator:** maintains the platform, monitors queues/cost/errors and investigates operational incidents.
- **OAuth providers:** authenticate external identities. OAuth is identity proof, not authorization; workspace authorization remains inside StudyOS.
- **External content providers:** user files, approved web pages and YouTube metadata/transcript sources. Their content is always untrusted data.
- **AI model providers:** LLM, embeddings and optional reranking.
- **Payment/notification providers:** post-MVP integrations, isolated behind outbound ports.

### 4.2 System Boundary Rule
The browser never receives infrastructure credentials, AI provider keys, RabbitMQ credentials or direct database access. All business authorization decisions are server-side.

## 5. C4 Level 2 - Containers

![C4 Container](diagrams/c4_container.png)

### 5.1 Web App - Next.js + TypeScript
Responsibilities:
- Authentication UX, notebook/source management and learning workflows.
- WebSocket client with reconnect/resume/dedup logic.
- PDF/web/video citation navigation.
- Transcript playback and Language Lab UI.
- Quiz/flashcard/review UX.
- Client-side optimistic updates only where server remains authoritative.

The Web App may upload directly to S3/MinIO using short-lived presigned URLs issued by Core API. It must not infer successful ingestion from upload completion; source readiness is server state.

### 5.2 Core API - Spring Boot Modular Monolith
The Core API owns **business truth**:
- identity/session lifecycle;
- workspaces, membership and RBAC;
- notebooks and source state machine;
- conversations and final message persistence;
- notes;
- artifact job lifecycle;
- quiz attempts/scoring;
- flashcard review records;
- mastery evidence and learning state;
- usage/quota enforcement;
- audit log and transactional outbox.

It is also the public WebSocket gateway. Browser connections terminate here so authorization, quotas, persistence and conversation state remain in one security boundary.

### 5.3 AI Service - FastAPI
The AI service owns **AI computation**, not business authorization:
- hybrid retrieval and reranking;
- context construction;
- model routing;
- grounded generation;
- citation validation;
- tutor modes;
- structured language analysis;
- AI evaluation hooks.

Every internal AI request contains a signed tenant context produced by Core API. The AI service validates the internal service token and still applies mandatory workspace/notebook/source filters to retrieval.

### 5.4 Async Workers - Python
Workers perform long-running or provider-bound work:
- parse/normalize source;
- transcript acquisition and segmentation;
- chunk/embed/index;
- concept extraction/enrichment;
- quiz/flashcard/study-guide generation.

Workers are independently scalable by queue. Worker outputs are idempotent. Business state changes are communicated as events and applied by Core API consumers, while derived chunk/vector data may be written by the responsible AI-data worker using a restricted DB role.

### 5.5 PostgreSQL + pgvector
Single source of truth and initial retrieval store. The design intentionally colocates relational metadata, full-text search and vectors to reduce early operational overhead. Split retrieval storage only after measured bottlenecks justify it.

### 5.6 Redis
Use Redis only for ephemeral/distributed coordination:
- rate-limit counters;
- WebSocket node routing/presence;
- short-lived stream replay buffer;
- selected caches;
- short locks where correctness is still enforced by durable storage.

Do not rely on Redis as the only store for messages, source status, mastery or review history.

### 5.7 RabbitMQ
RabbitMQ handles durable asynchronous workflows. It is not the primary business datastore. Messages are at-least-once and consumers must be idempotent.

### 5.8 Object Storage
Original uploads and large normalized artifacts are stored in S3/MinIO. Access uses short-lived signed URLs and server-side authorization.

## 6. C4 Level 3 - Core Backend Components

![Core Component](diagrams/c4_component_core.png)

### 6.1 Dependency Direction
Within each Spring module use the rule:

`api/inbound -> application -> domain <- infrastructure/outbound`

Domain code must not depend on Spring MVC, JPA, RabbitMQ, S3 SDK or AI provider clients. Infrastructure implements domain/application ports.

### 6.2 Module Communication
Preferred order:
1. same-module direct application call;
2. cross-module application interface when synchronous consistency is required;
3. local domain/application event for decoupled in-process reactions;
4. transactional outbox + RabbitMQ for asynchronous cross-process reactions.

Forbidden:
- one module directly using another module's repository;
- controllers bypassing application services;
- AI worker directly changing a user's mastery score;
- retrieval queries without explicit tenant scope.

## 7. C4 Level 3 - AI Service Components

![AI Component](diagrams/c4_component_ai.png)

### 7.1 Chat Pipeline
`Chat Orchestrator -> Retrieval -> Context Builder -> Grounded Generator -> Citation Validator -> Stream`

Each step has a versioned configuration and trace span. Retrieval results are assigned stable citation keys (`C1..Cn`). Model output may reference only keys provided in context. The validator rejects or downgrades unsupported citations before a final grounded response is persisted.

### 7.2 Tutor Pipeline
Tutor modes use the same retrieval and grounding primitives, plus learner state supplied by Core API. Tutor output cannot directly mutate mastery. It may propose or score an evidence-producing activity, whose result is subsequently validated and persisted by the Learning module.

### 7.3 Model Gateway
All provider-specific SDKs are hidden behind a model gateway with functions equivalent to:
- `generate()`;
- `stream()`;
- `embed()`;
- optional `rerank()`.

Every call records model, provider, prompt/config version, latency, token usage, retries and trace ID.

## 8. Runtime Trust Boundaries

### 8.1 Browser -> Core API
Untrusted public boundary. Requires access token/session, input validation, rate limiting and workspace authorization.

### 8.2 Core API -> AI Service
Internal boundary but still authenticated. Use short-lived service JWT/mTLS-compatible design. Internal context carries `userId`, `workspaceId`, `notebookId`, explicit `sourceIds`, permissions and trace ID.

### 8.3 Worker -> Database/Object Storage
Workers use dedicated least-privilege credentials. A parse worker does not need permission to mutate workspace membership. An embedding worker should not be able to issue refresh tokens.

### 8.4 Retrieved Content -> LLM
Treat all retrieved source text as untrusted data. It can supply facts but cannot grant permissions, change system instructions, select tools or disclose secrets.

## 9. Data Ownership Matrix

| Data | Owner | Other access |
|---|---|---|
| User/session/workspace | Core Identity/Workspace | read-only references by other modules |
| Notebook/source lifecycle | Core Notebook/Source | workers receive IDs and job inputs |
| Original file | Object Storage, referenced by Source module | workers read via scoped credentials |
| Normalized sections/chunks/embeddings | AI data pipeline | Core reads metadata; AI retrieval reads scoped chunks |
| Conversation/messages/citations | Core Conversation | AI produces candidate answer/citations, Core persists final state |
| Quiz/attempt/answers | Core Quiz/Studio | artifact worker proposes validated questions |
| Flashcard/reviews | Core Review | AI may generate card content; scheduler is deterministic Core logic |
| Mastery/evidence | Core Learning | AI/tutor may generate evidence candidates only |
| AI run telemetry | AI + operational store | Analytics reads aggregated data |
| Outbox/audit | Core platform | operational tooling only |

## 10. Consistency Model
Use strong transaction consistency for:
- workspace membership changes;
- source state transition + outbox insertion;
- quiz completion + evidence insertion;
- flashcard review + scheduler state update + evidence insertion;
- final assistant message + citations + usage persistence.

Use eventual consistency for:
- source parsing/indexing/enrichment;
- knowledge concept extraction;
- analytics aggregation;
- generated artifacts;
- recommendation regeneration.

## 11. Source Processing State Machine
Canonical states:

`CREATED -> UPLOADING -> QUEUED -> PARSING -> NORMALIZED -> CHUNKING -> EMBEDDING -> ENRICHING -> READY`

Failure from a processing state moves to `FAILED` with `failureCode`, safe user-facing message and `retryable` flag. Deletion moves through `DELETING -> DELETED` and must remove/revoke original objects and derived data.

State transitions are persisted and validated. Worker logs alone never define source state.

## 12. Chat Consistency and Streaming Strategy
External WebSocket terminates at Core API. Core API:
1. validates socket token and conversation access;
2. persists user message using `requestId` as idempotency key;
3. sends a signed internal streaming request to FastAPI;
4. forwards ordered AI events to browser;
5. buffers a short replay window in Redis;
6. on completion, persists assistant message/citations/usage transactionally;
7. supports reconnect using last received sequence.

If Redis is lost, durable conversation history remains correct. A reconnect may lose only transient partial tokens and can fall back to persisted message state.

## 13. Failure and Degradation Strategy

| Failure | Expected behavior |
|---|---|
| LLM provider unavailable | Chat fails with retryable error; notebooks/notes/review remain available |
| Embedding provider unavailable | Source remains processing/failed; uploaded file is retained for retry |
| RabbitMQ unavailable | Business mutation commits with outbox record; publisher retries later |
| Worker crashes after writing chunks | Duplicate delivery is safe through deterministic chunk keys/processed event IDs |
| Redis unavailable | Disable noncritical cache; WS replay/routing degrades; durable history unaffected |
| Object storage transient failure | Retry idempotent reads/uploads; source does not falsely become READY |
| Reranker fails | Fallback to fused retrieval results if configured and trace degradation |
| Citation validator finds unsupported key | Remove citation and mark answer PARTIAL/INSUFFICIENT; never fabricate source metadata |

## 14. Scalability Evolution

### Stage A - Beta
- 1 Core API instance;
- 1 AI service instance;
- 1 parse/index worker process with low concurrency;
- PostgreSQL/pgvector;
- Redis/RabbitMQ/MinIO via Docker Compose locally, managed equivalents in production.

### Stage B - Small Production
- horizontal Core API and AI instances;
- worker pools separated by job class;
- Redis for WS routing;
- managed PostgreSQL, Redis and object storage;
- CDN for static/download traffic;
- queue depth-based worker autoscaling.

### Service Extraction Triggers
Split a module only if at least one is demonstrated:
- independent scaling bottleneck;
- materially different availability/SLO;
- independent release/team ownership;
- security/data-isolation requirement;
- database contention that cannot be solved locally;
- measured deployment coupling causing operational cost.

## 15. Performance and SLO Design Targets
- Core CRUD p95: < 300 ms excluding external providers.
- Chat TTFT p95 target: < 2 s when upstream model permits; separately record provider latency.
- Source processing success: >= 98% for supported valid sources, excluding external access blocks.
- Core API availability beta target: 99.5% monthly.
- Cross-tenant data access: zero tolerance.
- Queue oldest-message age alert thresholds are job-class-specific.

## 16. Deployment Topology
Local:
- Next.js;
- Spring Boot Core;
- FastAPI AI;
- worker process(es);
- PostgreSQL + pgvector;
- Redis;
- RabbitMQ;
- MinIO;
- OpenTelemetry collector optional.

Production baseline:
`Cloudflare/CDN -> AWS ALB -> ECS/Fargate or EC2 services -> RDS PostgreSQL -> ElastiCache -> S3 -> RabbitMQ managed/controlled broker -> observability backend`.

Terraform owns infrastructure. Application images are immutable and versioned by commit SHA.

## 17. Architecture Freeze Decisions for Sprint 1
- Use modular monolith for Core API.
- Browser WebSocket terminates at Core API, not directly at FastAPI.
- Use PostgreSQL + pgvector + PostgreSQL FTS for first retrieval store.
- Use RabbitMQ + transactional outbox for source/artifact asynchronous jobs.
- Use presigned object uploads.
- Use application-level tenant authorization with mandatory scope fields; evaluate RLS later as defense-in-depth.
- Use UUID identifiers.
- Public contracts versioned under `/api/v1`; internal AI contracts under `/internal/v1`.
- Every distributed call/event propagates `traceId` and correlation identifiers.

