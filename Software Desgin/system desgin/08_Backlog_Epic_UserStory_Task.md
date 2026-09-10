# StudyOS Phase 0 - Implementation Backlog

## 1. Backlog Rules

- Hierarchy: Epic -> User Story -> Task.
- `P0` closes the production-capable MVP vertical loop; `P1` deepens differentiation or production quality.
- Story points measure relative uncertainty/complexity, not elapsed days. Task day estimates are planning aids for a solo developer and must be recalibrated.
- A story is Done only when acceptance criteria, authorization, tests, telemetry and contract changes are complete.

## 2. Epic Summary

| Epic | Goal | Priority | Planned phase | Stories |
|---|---|---|---|---:|
| E0 Engineering Foundation | Create a reproducible repo, local runtime, CI and architecture guardrails before feature work. | P0 | Sprint 0 | 4 |
| E1 Identity & Tenant Isolation | Authenticate users and enforce workspace-scoped authorization as a platform invariant. | P0 | Sprint 1 | 4 |
| E2 Workspace & Notebook | Give learners a durable tenant-scoped place to organize goals and sources. | P0 | Sprint 1-2 | 2 |
| E3 Source Ingestion Platform | Turn heterogeneous learning sources into durable normalized, searchable data with visible processing state. | P0 | Sprint 2-4 | 6 |
| E4 Retrieval & Grounded Chat | Answer questions from user sources with validated citations and resilient real-time streaming. | P0 | Sprint 5-7 | 5 |
| E5 Notes & Studio | Turn evidence into reusable notes and generated learning artifacts. | P1 | Sprint 8-9 | 2 |
| E6 Quiz & Flashcard Active Learning | Convert passive source consumption into evidence-producing practice and spaced review. | P0 | Sprint 9-11 | 4 |
| E7 Learning State & Adaptive Recommendations | Maintain explainable concept-level learner state and recommend the next high-value action. | P0 | Sprint 10-12 | 4 |
| E8 YouTube Language Lab | Turn authentic video transcripts into integrated translation, vocabulary and spaced review. | P1 | Sprint 12-13 | 4 |
| E9 Adaptive Tutor Modes | Use learning state and source evidence to teach interactively without letting the model become the source of truth. | P1 | Sprint 14 | 2 |
| E10 Observability, Security & Production | Make the beta deployable, diagnosable and resilient enough for real users. | P0 | Sprint 0-16 | 6 |
| E11 Product Analytics & Beta Validation | Measure activation, learning loops and reliability with real users instead of optimizing feature count. | P1 | Sprint 15-16 | 3 |

## 3. Detailed Epic / User Story / Task Backlog

### E0 - Engineering Foundation

**Goal:** Create a reproducible repo, local runtime, CI and architecture guardrails before feature work.  
**Priority / Phase:** P0 / Sprint 0

#### US-0001 - Bootstrap monorepo

**User story:** As a **Developer**, I want to **bootstrap monorepo** so that I can **all apps build from one documented repository**.

**Priority:** P0  |  **Story points:** 3  |  **Depends on:** None

**Acceptance criteria:**
- Repo contains web, core-api, ai-service, worker, infrastructure, docs and tests roots.
- Local README documents supported toolchain and startup commands.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0001-T01 - Create monorepo directories and root conventions | Platform | 1 d |
| US-0001-T02 - Create Spring Boot Core skeleton | Core | 1 d |
| US-0001-T03 - Create FastAPI service skeleton | AI | 1 d |
| US-0001-T04 - Create worker skeleton | Worker | 1 d |
| US-0001-T05 - Create Next.js web skeleton | Web | 1 d |
| US-0001-T06 - Add editorconfig, formatters and lint configs | Platform | 1 d |

#### US-0002 - Local infrastructure with Docker Compose

**User story:** As a **Developer**, I want to **local infrastructure with docker compose** so that I can **run all required local dependencies predictably**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0001

**Acceptance criteria:**
- Docker Compose starts PostgreSQL/pgvector, Redis, RabbitMQ and MinIO.
- Health checks make dependency readiness observable.
- Local secrets are example-only and not production credentials.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0002-T01 - Add PostgreSQL pgvector container and init scripts | Platform | 1 d |
| US-0002-T02 - Add Redis, RabbitMQ and MinIO services | Platform | 1 d |
| US-0002-T03 - Add health checks and named volumes | Platform | 1 d |
| US-0002-T04 - Create .env.example and configuration docs | Platform | 1 d |
| US-0002-T05 - Add one-command local startup smoke script | Platform | 1 d |

#### US-0003 - CI quality gate

**User story:** As a **Developer**, I want to **ci quality gate** so that I can **catch formatting, test and contract failures before merge**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0001

**Acceptance criteria:**
- Pull requests run format/lint, unit tests and YAML/JSON contract parsing.
- Build fails on architecture rule violations once Core modules exist.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0003-T01 - Create GitHub Actions workflow for Java | DevOps | 1 d |
| US-0003-T02 - Create Python lint/test workflow | DevOps | 1 d |
| US-0003-T03 - Create TypeScript lint/test workflow | DevOps | 1 d |
| US-0003-T04 - Add OpenAPI/AsyncAPI/JSON schema parsing checks | DevOps | 1 d |
| US-0003-T05 - Cache build dependencies safely | DevOps | 1 d |

#### US-0004 - Database migration baseline

**User story:** As a **Developer**, I want to **database migration baseline** so that I can **have one controlled owner for schema evolution**.

**Priority:** P0  |  **Story points:** 3  |  **Depends on:** US-0002

**Acceptance criteria:**
- Initial migration creates required extensions/tables/indexes.
- Migration can run from empty DB repeatedly in CI.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0004-T01 - Choose Flyway or Liquibase and configure Core | Core | 1 d |
| US-0004-T02 - Translate Phase 0 schema.sql into V1 migration | Database | 2 d |
| US-0004-T03 - Add migration integration test on clean PostgreSQL | Test | 1 d |

### E1 - Identity & Tenant Isolation

**Goal:** Authenticate users and enforce workspace-scoped authorization as a platform invariant.  
**Priority / Phase:** P0 / Sprint 1

#### US-0101 - Register and login

**User story:** As a **Learner**, I want to **register and login** so that I can **create an account and obtain a secure session**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0004

**Acceptance criteria:**
- Valid registration creates active user and tokens.
- Passwords are hashed; duplicate email is rejected.
- Invalid credentials return stable error without leaking which field was wrong.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0101-T01 - Implement User aggregate/repository | Core | 2 d |
| US-0101-T02 - Implement password hashing policy | Security | 1 d |
| US-0101-T03 - Implement register/login commands and controller | Core | 2 d |
| US-0101-T04 - Implement JWT access token service | Security | 2 d |
| US-0101-T05 - Add auth integration/negative tests | Test | 2 d |

#### US-0102 - Refresh token rotation and logout

**User story:** As a **Learner**, I want to **refresh token rotation and logout** so that I can **remain signed in safely and revoke sessions**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0101

**Acceptance criteria:**
- Refresh token is stored hashed and rotated on use.
- Reusing a revoked token is rejected.
- Logout revokes the target refresh session.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0102-T01 - Implement RefreshSession domain model | Core | 1 d |
| US-0102-T02 - Implement hashed refresh token persistence | Security | 1 d |
| US-0102-T03 - Implement rotate/revoke flow | Core | 2 d |
| US-0102-T04 - Add token expiry/rotation tests | Test | 2 d |

#### US-0103 - Workspace membership and RBAC

**User story:** As a **Learner**, I want to **workspace membership and rbac** so that I can **access only workspaces where I am a member**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0101

**Acceptance criteria:**
- Workspace creator is OWNER.
- Non-member cannot read or mutate workspace resources.
- Role checks are reusable across modules.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0103-T01 - Implement Workspace and WorkspaceMember aggregates | Core | 2 d |
| US-0103-T02 - Implement WorkspaceAuthorization application interface | Core | 2 d |
| US-0103-T03 - Implement workspace endpoints | Core | 2 d |
| US-0103-T04 - Add authorization matrix tests | Security | 3 d |
| US-0103-T05 - Add audit event for membership changes | Observability | 1 d |

#### US-0104 - Google OAuth sign-in

**User story:** As a **Learner**, I want to **google oauth sign-in** so that I can **sign in using an external identity without weakening StudyOS authorization**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-0101

**Acceptance criteria:**
- External subject maps to one internal user identity.
- OAuth provider claims never directly grant workspace access.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0104-T01 - Add OAuth provider adapter | Core | 2 d |
| US-0104-T02 - Implement identity linking rules | Core | 2 d |
| US-0104-T03 - Add callback/error handling | Web | 1 d |
| US-0104-T04 - Test account/link collision cases | Test | 2 d |

### E2 - Workspace & Notebook

**Goal:** Give learners a durable tenant-scoped place to organize goals and sources.  
**Priority / Phase:** P0 / Sprint 1-2

#### US-0201 - Create and manage notebooks

**User story:** As a **Learner**, I want to **create and manage notebooks** so that I can **organize one learning topic and goal**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0103

**Acceptance criteria:**
- Member can create/list/read/update/archive notebooks in workspace.
- Cross-workspace notebook IDs are forbidden/not found safely.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0201-T01 - Implement Notebook aggregate and repository | Core | 2 d |
| US-0201-T02 - Implement notebook application service | Core | 1 d |
| US-0201-T03 - Implement REST endpoints from OpenAPI | Core | 2 d |
| US-0201-T04 - Add indexes/query tests | Database | 1 d |
| US-0201-T05 - Build notebook list/create UI | Web | 2 d |

#### US-0202 - Notebook goal metadata

**User story:** As a **Learner**, I want to **notebook goal metadata** so that I can **state what I am trying to learn so later recommendations have context**.

**Priority:** P1  |  **Story points:** 3  |  **Depends on:** US-0201

**Acceptance criteria:**
- Notebook can store goal text separately from title/description.
- Goal metadata is returned to AI only through server-created context.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0202-T01 - Add goalText validation and persistence | Core | 1 d |
| US-0202-T02 - Add notebook settings UI | Web | 1 d |
| US-0202-T03 - Add contract/integration tests | Test | 1 d |

### E3 - Source Ingestion Platform

**Goal:** Turn heterogeneous learning sources into durable normalized, searchable data with visible processing state.  
**Priority / Phase:** P0 / Sprint 2-4

#### US-0301 - Presigned PDF upload

**User story:** As a **Learner**, I want to **presigned pdf upload** so that I can **upload a document without tying up the Core API process**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0201

**Acceptance criteria:**
- Upload-init validates membership, MIME, size and quota.
- Browser uploads using short-lived signed URL.
- Upload-complete queues processing only after source/object checks.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0301-T01 - Implement Source/SourceVersion aggregates and state machine | Core | 3 d |
| US-0301-T02 - Implement ObjectStoragePort and MinIO/S3 adapter | Core | 2 d |
| US-0301-T03 - Implement upload-init endpoint | Core | 2 d |
| US-0301-T04 - Implement upload-complete endpoint and checksum checks | Core | 2 d |
| US-0301-T05 - Build upload UI with status polling | Web | 2 d |
| US-0301-T06 - Add file size/MIME/security tests | Security | 2 d |

#### US-0302 - Transactional outbox publisher

**User story:** As a **Platform**, I want to **transactional outbox publisher** so that I can **never lose a source-processing request after database commit**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0301

**Acceptance criteria:**
- Source queue transition and outbox insert commit atomically.
- Publisher marks event published only after broker confirm.
- Restarting publisher does not lose unpublished rows.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0302-T01 - Implement outbox entity/repository | Core | 2 d |
| US-0302-T02 - Implement outbox insertion in source transaction | Core | 1 d |
| US-0302-T03 - Implement publisher batching with SKIP LOCKED | Core | 2 d |
| US-0302-T04 - Enable RabbitMQ publisher confirms | Messaging | 1 d |
| US-0302-T05 - Add crash/retry integration tests | Test | 3 d |

#### US-0303 - PDF parse and normalize worker

**User story:** As a **Learner**, I want to **pdf parse and normalize worker** so that I can **convert uploaded PDF into structured text while preserving provenance**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0302

**Acceptance criteria:**
- Worker consumes parse request idempotently.
- Normalized output preserves section/page information where parser provides it.
- Corrupt/unsupported documents become explicit FAILED state.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0303-T01 - Implement RabbitMQ consumer foundation | Worker | 2 d |
| US-0303-T02 - Implement processed_events idempotency helper | Worker | 2 d |
| US-0303-T03 - Implement PDF parser adapter | Worker | 3 d |
| US-0303-T04 - Define normalized document schema | AI | 2 d |
| US-0303-T05 - Write normalized artifact to object storage | Worker | 1 d |
| US-0303-T06 - Publish source.parsed/source.failed events | Worker | 1 d |
| US-0303-T07 - Add parser fixture tests | Test | 2 d |

#### US-0304 - Chunk and embedding index

**User story:** As a **Learner**, I want to **chunk and embedding index** so that I can **make normalized source retrievable by semantic and lexical search**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0303

**Acceptance criteria:**
- Chunks are deterministic and linked to page/section/time metadata.
- Duplicate event does not create duplicate chunks.
- Each chunk has embedding/config version and FTS representation.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0304-T01 - Implement section-aware chunker | AI | 3 d |
| US-0304-T02 - Implement embedding provider interface | AI | 2 d |
| US-0304-T03 - Implement index worker and batch embeddings | Worker | 3 d |
| US-0304-T04 - Persist chunks/pgvector records idempotently | Database | 2 d |
| US-0304-T05 - Create FTS/vector indexes and explain plans | Database | 2 d |
| US-0304-T06 - Publish source.indexed event | Worker | 1 d |

#### US-0305 - Concept enrichment and READY transition

**User story:** As a **Learner**, I want to **concept enrichment and ready transition** so that I can **have source concepts available for later learning features**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0304

**Acceptance criteria:**
- Concept candidates retain supporting chunk IDs and confidence.
- Duplicate normalized concepts merge deterministically within notebook.
- Core marks source READY only on valid completion event.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0305-T01 - Implement concept extraction schema/prompt | AI | 3 d |
| US-0305-T02 - Implement concept normalization/merge | AI | 2 d |
| US-0305-T03 - Persist concepts/chunk_concepts safely | Database | 2 d |
| US-0305-T04 - Publish source.ready.v1 | Worker | 1 d |
| US-0305-T05 - Implement Core source event consumer/state validation | Core | 2 d |

#### US-0306 - URL and raw text sources

**User story:** As a **Learner**, I want to **url and raw text sources** so that I can **add useful material without file upload**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-0302

**Acceptance criteria:**
- WEB/RAW source respects same notebook scope and state model.
- HTML is sanitized/normalized as untrusted content.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0306-T01 - Implement raw text source endpoint | Core | 1 d |
| US-0306-T02 - Implement safe URL fetch policy/SSRF controls | Security | 3 d |
| US-0306-T03 - Implement HTML extraction/sanitization worker | Worker | 2 d |
| US-0306-T04 - Add URL content tests and block private-network targets | Test | 2 d |

### E4 - Retrieval & Grounded Chat

**Goal:** Answer questions from user sources with validated citations and resilient real-time streaming.  
**Priority / Phase:** P0 / Sprint 5-7

#### US-0401 - Hybrid retrieval baseline

**User story:** As a **Learner**, I want to **hybrid retrieval baseline** so that I can **retrieve the most relevant source evidence for a question**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0304

**Acceptance criteria:**
- Retrieval always applies workspace/notebook filters.
- Vector and lexical results are fused into one ranked candidate list.
- Debug mode exposes scores/config version to trusted users/tests.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0401-T01 - Implement tenant-scoped chunk repository | AI | 2 d |
| US-0401-T02 - Implement query embedding | AI | 1 d |
| US-0401-T03 - Implement vector search | AI | 2 d |
| US-0401-T04 - Implement PostgreSQL FTS search | AI | 2 d |
| US-0401-T05 - Implement reciprocal-rank fusion | AI | 2 d |
| US-0401-T06 - Add retrieval unit/integration dataset tests | Test | 2 d |

#### US-0402 - Grounded answer and citation validation

**User story:** As a **Learner**, I want to **grounded answer and citation validation** so that I can **verify where AI claims come from**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0401

**Acceptance criteria:**
- Model receives only citation keys assigned by server.
- Every returned citation key maps to a retrieved chunk.
- Insufficient evidence produces explicit fallback, not invented citations.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0402-T01 - Implement context budgeter/citation-key assignment | AI | 2 d |
| US-0402-T02 - Implement grounded answer structured schema | AI | 2 d |
| US-0402-T03 - Implement citation validator | AI | 2 d |
| US-0402-T04 - Implement source metadata materialization in Core | Core | 2 d |
| US-0402-T05 - Add hallucinated-citation adversarial tests | Security | 2 d |

#### US-0403 - Conversation persistence

**User story:** As a **Learner**, I want to **conversation persistence** so that I can **keep durable chat history per notebook**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0201

**Acceptance criteria:**
- Conversation/message order is deterministic.
- User can only open conversations in permitted notebook.
- Feedback can be recorded per assistant message.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0403-T01 - Implement Conversation/Message/Citation entities | Core | 2 d |
| US-0403-T02 - Implement conversation REST endpoints | Core | 2 d |
| US-0403-T03 - Implement message feedback endpoint | Core | 1 d |
| US-0403-T04 - Add pagination/order tests | Test | 1 d |

#### US-0404 - Production WebSocket chat

**User story:** As a **Learner**, I want to **production websocket chat** so that I can **receive streamed AI answers and recover from transient connection loss**.

**Priority:** P0  |  **Story points:** 13  |  **Depends on:** US-0402, US-0403

**Acceptance criteria:**
- Short-lived token authorizes exactly one permitted conversation.
- Duplicate requestId does not create a second message.
- Client can cancel and resume from buffered sequence.
- Final message/citations remain durable after stream completion.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0404-T01 - Implement WS token issuance | Core | 2 d |
| US-0404-T02 - Implement WebSocket handshake/auth/origin validation | Core | 3 d |
| US-0404-T03 - Implement client command parser and protocol envelope | Core | 2 d |
| US-0404-T04 - Implement Core -> FastAPI internal streaming client | Core | 3 d |
| US-0404-T05 - Implement FastAPI chat stream endpoint | AI | 3 d |
| US-0404-T06 - Implement sequence allocation + Redis replay buffer | Core | 3 d |
| US-0404-T07 - Implement cancel/reconnect/resume behavior | Core | 3 d |
| US-0404-T08 - Build typed WebSocket client and chat UI | Web | 4 d |
| US-0404-T09 - Add WS integration/concurrency tests | Test | 4 d |

#### US-0405 - Reranking and RAG evaluation gate

**User story:** As a **Platform**, I want to **reranking and rag evaluation gate** so that I can **improve retrieval only when evaluation demonstrates value**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0401

**Acceptance criteria:**
- Versioned eval dataset can compare baseline and candidate retrieval configs.
- Release report includes retrieval and citation metrics plus latency/cost.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0405-T01 - Create representative eval dataset format | AI | 2 d |
| US-0405-T02 - Implement Recall@K/MRR/NDCG/context precision scripts | AI | 3 d |
| US-0405-T03 - Add optional reranker adapter | AI | 2 d |
| US-0405-T04 - Version retrieval configuration | AI | 1 d |
| US-0405-T05 - Add CI/offline eval command and report artifact | DevOps | 2 d |

### E5 - Notes & Studio

**Goal:** Turn evidence into reusable notes and generated learning artifacts.  
**Priority / Phase:** P1 / Sprint 8-9

#### US-0501 - Source-linked notes

**User story:** As a **Learner**, I want to **source-linked notes** so that I can **save knowledge with provenance instead of losing it in chat history**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-0403

**Acceptance criteria:**
- Note belongs to notebook/user and may reference valid citations.
- Cross-notebook citation attachment is rejected.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0501-T01 - Implement Note/NoteCitation entities | Core | 2 d |
| US-0501-T02 - Implement notes CRUD and authorization | Core | 2 d |
| US-0501-T03 - Build note editor with citation chips | Web | 2 d |
| US-0501-T04 - Add ownership/provenance tests | Test | 1 d |

#### US-0502 - Artifact job framework

**User story:** As a **Learner**, I want to **artifact job framework** so that I can **request generated study artifacts without blocking HTTP**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0302, US-0402

**Acceptance criteria:**
- Generation request returns 202/job state.
- Duplicate idempotency key returns same logical job.
- Failure and retry status is visible.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0502-T01 - Implement ArtifactJob aggregate | Core | 2 d |
| US-0502-T02 - Implement artifact generation endpoints/outbox | Core | 2 d |
| US-0502-T03 - Implement artifact worker dispatch | Worker | 2 d |
| US-0502-T04 - Implement AI structured artifact contract | AI | 2 d |
| US-0502-T05 - Implement job status endpoint/UI | Web | 2 d |

### E6 - Quiz & Flashcard Active Learning

**Goal:** Convert passive source consumption into evidence-producing practice and spaced review.  
**Priority / Phase:** P0 / Sprint 9-11

#### US-0601 - Grounded quiz generation

**User story:** As a **Learner**, I want to **grounded quiz generation** so that I can **practice exactly the concepts supported by my selected sources**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0502

**Acceptance criteria:**
- Question includes concept/source provenance.
- Server rejects malformed/unsupported generated question payload.
- Answer key is not exposed before completion where inappropriate.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0601-T01 - Implement quiz generation prompt/schema | AI | 3 d |
| US-0601-T02 - Implement quiz persistence mapper/validator | Core | 2 d |
| US-0601-T03 - Implement question-concept/source linkage | Database | 1 d |
| US-0601-T04 - Build quiz rendering UI | Web | 3 d |
| US-0601-T05 - Add groundedness/schema tests | Test | 2 d |

#### US-0602 - Quiz attempt and scoring

**User story:** As a **Learner**, I want to **quiz attempt and scoring** so that I can **complete a quiz and receive consistent results**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0601

**Acceptance criteria:**
- Attempt can save/replace answers until completion.
- Complete is idempotent and locks final score.
- Objective questions score deterministically.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0602-T01 - Implement QuizAttempt/QuizAnswer aggregates | Core | 3 d |
| US-0602-T02 - Implement start/save/complete endpoints | Core | 3 d |
| US-0602-T03 - Implement deterministic scoring strategies | Core | 2 d |
| US-0602-T04 - Build attempt/result UI | Web | 3 d |
| US-0602-T05 - Add duplicate-complete and authorization tests | Test | 2 d |

#### US-0603 - Flashcard generation

**User story:** As a **Learner**, I want to **flashcard generation** so that I can **turn source concepts into reviewable recall prompts**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0502

**Acceptance criteria:**
- Generated cards preserve source/chunk/concept provenance where available.
- Duplicate generation does not silently duplicate same job.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0603-T01 - Implement flashcard generation schema/prompt | AI | 2 d |
| US-0603-T02 - Persist deck/cards and provenance | Core | 2 d |
| US-0603-T03 - Build deck/card UI | Web | 2 d |

#### US-0604 - Spaced review scheduler

**User story:** As a **Learner**, I want to **spaced review scheduler** so that I can **review cards at deterministic due times based on my history**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0603

**Acceptance criteria:**
- Queue returns only current user due cards.
- Grade produces deterministic next state/date.
- Concurrent duplicate review cannot be applied twice.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0604-T01 - Define ReviewScheduler interface/state model | Core | 2 d |
| US-0604-T02 - Implement initial validated scheduling algorithm | Core | 3 d |
| US-0604-T03 - Implement review queue query | Core | 2 d |
| US-0604-T04 - Implement grade transaction/history | Core | 3 d |
| US-0604-T05 - Build review UI | Web | 3 d |
| US-0604-T06 - Add property/unit tests for scheduler | Test | 3 d |

### E7 - Learning State & Adaptive Recommendations

**Goal:** Maintain explainable concept-level learner state and recommend the next high-value action.  
**Priority / Phase:** P0 / Sprint 10-12

#### US-0701 - Mastery evidence from quiz

**User story:** As a **Learner**, I want to **mastery evidence from quiz** so that I can **have demonstrated quiz performance update my learning state**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0602, US-0305

**Acceptance criteria:**
- Quiz completion creates evidence per covered concept.
- Mastery and confidence update is explainable from stored evidence.
- Repeating completion does not duplicate evidence.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0701-T01 - Define mastery update algorithm v0 | Core | 3 d |
| US-0701-T02 - Implement MasteryEvidence append model | Core | 2 d |
| US-0701-T03 - Implement mastery projection update with concurrency control | Core | 3 d |
| US-0701-T04 - Wire quiz completion to evidence | Core | 2 d |
| US-0701-T05 - Add algorithm examples/golden tests | Test | 2 d |

#### US-0702 - Retention evidence from flashcard reviews

**User story:** As a **Learner**, I want to **retention evidence from flashcard reviews** so that I can **have delayed recall affect mastery more than passive viewing**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0604, US-0701

**Acceptance criteria:**
- Concept-linked review creates weighted evidence.
- Immediate repeated reviews do not unrealistically inflate mastery.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0702-T01 - Define recall evidence weighting rule | Core | 2 d |
| US-0702-T02 - Wire review transaction to mastery evidence | Core | 2 d |
| US-0702-T03 - Add delayed/immediate review tests | Test | 2 d |

#### US-0703 - Mastery and concept APIs

**User story:** As a **Learner**, I want to **mastery and concept apis** so that I can **see what I know, what is uncertain and why**.

**Priority:** P0  |  **Story points:** 5  |  **Depends on:** US-0701

**Acceptance criteria:**
- Mastery API returns score, confidence, evidence count and last evidence time.
- Concept API returns notebook-scoped concept relationships.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0703-T01 - Implement concept projection query | Core | 2 d |
| US-0703-T02 - Implement mastery query endpoint | Core | 2 d |
| US-0703-T03 - Build mastery/weak-concepts UI | Web | 2 d |

#### US-0704 - Explainable recommendation engine v0

**User story:** As a **Learner**, I want to **explainable recommendation engine v0** so that I can **know the best next learning action based on goal, gaps and review urgency**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0702, US-0202

**Acceptance criteria:**
- Recommendation priority uses transparent rules/weights.
- Every recommendation includes reason factors.
- Engine never recommends inaccessible notebook/source.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0704-T01 - Define candidate action model and score weights | Core | 2 d |
| US-0704-T02 - Implement recommendation generation service | Core | 3 d |
| US-0704-T03 - Persist recommendation snapshot/reason JSON | Database | 1 d |
| US-0704-T04 - Implement recommendations endpoint | Core | 2 d |
| US-0704-T05 - Build Today/Next Action UI | Web | 3 d |
| US-0704-T06 - Add explainability/authorization tests | Test | 2 d |

### E8 - YouTube Language Lab

**Goal:** Turn authentic video transcripts into integrated translation, vocabulary and spaced review.  
**Priority / Phase:** P1 / Sprint 12-13

#### US-0801 - YouTube source and transcript pipeline

**User story:** As a **Language learner**, I want to **youtube source and transcript pipeline** so that I can **import a video as a timestamped learning source when transcript access is available**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0302

**Acceptance criteria:**
- URL is classified as YOUTUBE and remains tenant scoped.
- Transcript provider abstraction returns timestamped segments or explicit unavailable state.
- Provider failure does not fabricate transcript text.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0801-T01 - Implement YouTube URL classifier/metadata parser | Worker | 2 d |
| US-0801-T02 - Define TranscriptProvider abstraction | Worker | 2 d |
| US-0801-T03 - Implement first permitted/manual provider adapter | Worker | 3 d |
| US-0801-T04 - Persist transcript segments | Database | 2 d |
| US-0801-T05 - Publish transcript ready/failure events | Worker | 1 d |

#### US-0802 - Transcript learning UI

**User story:** As a **Language learner**, I want to **transcript learning ui** so that I can **navigate video and transcript together**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-0801

**Acceptance criteria:**
- Transcript displays timestamped segments.
- Selecting a segment can seek video and launch analysis.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0802-T01 - Implement transcript REST endpoint | Core | 1 d |
| US-0802-T02 - Build synchronized video/transcript panel | Web | 3 d |
| US-0802-T03 - Add segment selection/seek behavior | Web | 2 d |

#### US-0803 - Sentence translation/vocabulary/grammar analysis

**User story:** As a **Language learner**, I want to **sentence translation/vocabulary/grammar analysis** so that I can **understand a selected authentic sentence without switching tools**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0802, US-0402

**Acceptance criteria:**
- Analysis returns structured translation, vocabulary and grammar fields.
- AI receives learner level and source sentence as data, not authority.
- Invalid model output is rejected/retried/fails safely.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0803-T01 - Implement language analysis Pydantic schemas | AI | 2 d |
| US-0803-T02 - Implement language prompt/model route | AI | 2 d |
| US-0803-T03 - Implement Core language endpoint/AI client | Core | 2 d |
| US-0803-T04 - Build analysis panel UI | Web | 3 d |
| US-0803-T05 - Add structured-output tests | Test | 2 d |

#### US-0804 - Save vocabulary to review

**User story:** As a **Language learner**, I want to **save vocabulary to review** so that I can **save a useful word/phrase with context and optionally create a flashcard**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-0803, US-0604

**Acceptance criteria:**
- Saved item retains source/segment context.
- Create-flashcard uses the existing Review module and scheduler.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0804-T01 - Implement VocabularyItem aggregate/repository | Core | 2 d |
| US-0804-T02 - Implement vocabulary REST endpoints | Core | 1 d |
| US-0804-T03 - Implement vocabulary-to-flashcard application port | Core | 2 d |
| US-0804-T04 - Build save/review UX | Web | 2 d |

### E9 - Adaptive Tutor Modes

**Goal:** Use learning state and source evidence to teach interactively without letting the model become the source of truth.  
**Priority / Phase:** P1 / Sprint 14

#### US-0901 - Socratic tutor mode

**User story:** As a **Learner**, I want to **socratic tutor mode** so that I can **be guided by questions rather than receiving an immediate answer**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0404, US-0703

**Acceptance criteria:**
- Mode uses notebook evidence and learner state context.
- Tutor can ask/follow up but cannot mutate mastery merely by conversation.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0901-T01 - Define tutor state contract | AI | 2 d |
| US-0901-T02 - Implement Socratic workflow | AI | 3 d |
| US-0901-T03 - Add mode handling in conversation protocol | Core | 2 d |
| US-0901-T04 - Build tutor mode selector/UX | Web | 2 d |
| US-0901-T05 - Add groundedness/tutor behavior tests | Test | 2 d |

#### US-0902 - Feynman evaluation mode

**User story:** As a **Learner**, I want to **feynman evaluation mode** so that I can **explain a concept and receive structured feedback on gaps/misconceptions**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0901

**Acceptance criteria:**
- AI returns structured rubric with correct/missing/misconception sections.
- Any mastery evidence from rubric is conservative and must pass Core validation.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-0902-T01 - Define Feynman rubric schema | AI | 2 d |
| US-0902-T02 - Implement grounded Feynman evaluator | AI | 3 d |
| US-0902-T03 - Define Core evidence policy for rubric scores | Core | 2 d |
| US-0902-T04 - Add evaluation calibration fixtures | Test | 2 d |

### E10 - Observability, Security & Production

**Goal:** Make the beta deployable, diagnosable and resilient enough for real users.  
**Priority / Phase:** P0 / Sprint 0-16

#### US-1001 - Distributed tracing and structured logs

**User story:** As a **Operator**, I want to **distributed tracing and structured logs** so that I can **trace one user action across Core, AI, RabbitMQ and workers**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0001

**Acceptance criteria:**
- HTTP/internal HTTP/RabbitMQ spans share trace context.
- Logs include service, environment, traceId and safe resource identifiers.
- Raw secrets/source content are not logged by default.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1001-T01 - Add OpenTelemetry to Core | Observability | 2 d |
| US-1001-T02 - Add OpenTelemetry to FastAPI/worker | Observability | 2 d |
| US-1001-T03 - Propagate trace/correlation headers/events | Observability | 2 d |
| US-1001-T04 - Implement structured JSON logging/redaction | Observability | 2 d |
| US-1001-T05 - Create trace smoke test | Test | 1 d |

#### US-1002 - Metrics and dashboards

**User story:** As a **Operator**, I want to **metrics and dashboards** so that I can **see platform, queue and AI health before users report problems**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-1001

**Acceptance criteria:**
- Dashboard includes API latency/errors, queue depth/age, worker failures, WS connections and AI cost/latency.
- Alert thresholds exist for critical failures.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1002-T01 - Expose Core/AI/worker metrics | Observability | 2 d |
| US-1002-T02 - Configure collector/metrics backend for local/staging | Observability | 2 d |
| US-1002-T03 - Create ingestion dashboard | Observability | 2 d |
| US-1002-T04 - Create chat/AI cost dashboard | Observability | 2 d |
| US-1002-T05 - Define baseline alerts | Operations | 2 d |

#### US-1003 - AI and tenant security test suite

**User story:** As a **Operator**, I want to **ai and tenant security test suite** so that I can **prevent prompt/data boundaries from becoming production vulnerabilities**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0402, US-0103

**Acceptance criteria:**
- Cross-tenant access tests exist for every high-risk resource.
- Prompt-injection corpus confirms retrieved content cannot grant tools/permissions.
- URL ingestion blocks SSRF to private/metadata networks.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1003-T01 - Build authorization matrix integration suite | Security | 3 d |
| US-1003-T02 - Build indirect prompt injection test corpus | Security | 3 d |
| US-1003-T03 - Add SSRF/private-network URL tests | Security | 2 d |
| US-1003-T04 - Add dependency/SAST scanning to CI | DevOps | 2 d |

#### US-1004 - Rate limits, quotas and cost accounting

**User story:** As a **Operator**, I want to **rate limits, quotas and cost accounting** so that I can **protect provider cost and platform capacity**.

**Priority:** P0  |  **Story points:** 8  |  **Depends on:** US-0101, US-0404

**Acceptance criteria:**
- AI requests are limited by user/plan and active-generation concurrency.
- Model/token/cost metrics are persisted per AI run.
- Quota errors are stable and visible to client.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1004-T01 - Implement Redis-backed rate limit abstraction | Core | 2 d |
| US-1004-T02 - Implement AI usage recording | AI | 2 d |
| US-1004-T03 - Implement plan quota policy | Core | 3 d |
| US-1004-T04 - Integrate quota checks in WS/artifact flows | Core | 2 d |
| US-1004-T05 - Add denial-of-wallet tests | Security | 2 d |

#### US-1005 - Production AWS deployment with Terraform

**User story:** As a **Operator**, I want to **production aws deployment with terraform** so that I can **deploy reproducibly without manual snowflake infrastructure**.

**Priority:** P0  |  **Story points:** 13  |  **Depends on:** US-0003, US-1001

**Acceptance criteria:**
- Terraform creates environment infrastructure from reviewed configuration.
- CI deploys immutable images to staging and supports controlled promotion.
- TLS, secrets, backups and rollback procedure are documented/tested.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1005-T01 - Design VPC/network/security groups | Infrastructure | 3 d |
| US-1005-T02 - Terraform RDS PostgreSQL and S3 | Infrastructure | 3 d |
| US-1005-T03 - Terraform Redis/broker/runtime services | Infrastructure | 4 d |
| US-1005-T04 - Configure secrets management and IAM least privilege | Security | 3 d |
| US-1005-T05 - Build/push immutable Docker images | DevOps | 2 d |
| US-1005-T06 - Implement staging deployment pipeline | DevOps | 3 d |
| US-1005-T07 - Document migration/rollback/runbooks | Operations | 3 d |
| US-1005-T08 - Run backup/restore rehearsal | Operations | 2 d |

#### US-1006 - Load and resilience test

**User story:** As a **Operator**, I want to **load and resilience test** so that I can **know the first bottlenecks before public beta**.

**Priority:** P1  |  **Story points:** 8  |  **Depends on:** US-0404, US-1002

**Acceptance criteria:**
- k6 test covers CRUD, chat start/WS concurrency and ingestion burst.
- Results capture p95, error rates, resource usage and bottlenecks.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1006-T01 - Create k6 REST scenarios | Test | 2 d |
| US-1006-T02 - Create WebSocket concurrency scenario | Test | 3 d |
| US-1006-T03 - Create ingestion burst scenario | Test | 2 d |
| US-1006-T04 - Run failure injection for provider/broker restart | Test | 2 d |
| US-1006-T05 - Write load-test report and tuning actions | Operations | 2 d |

### E11 - Product Analytics & Beta Validation

**Goal:** Measure activation, learning loops and reliability with real users instead of optimizing feature count.  
**Priority / Phase:** P1 / Sprint 15-16

#### US-1101 - Product event taxonomy

**User story:** As a **Product owner**, I want to **product event taxonomy** so that I can **measure the full source-to-mastery funnel**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-0001

**Acceptance criteria:**
- Events have stable names/properties and avoid sensitive raw content.
- Activation and learning-loop events can be queried end-to-end.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1101-T01 - Define analytics event catalog | Analytics | 2 d |
| US-1101-T02 - Implement server-side event emitter | Core | 2 d |
| US-1101-T03 - Instrument web funnel events | Web | 2 d |
| US-1101-T04 - Validate events in staging | Test | 1 d |

#### US-1102 - Learning overview analytics

**User story:** As a **Learner**, I want to **learning overview analytics** so that I can **see outcome-focused progress rather than only time spent**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-0703, US-1101

**Acceptance criteria:**
- Overview includes study activity, quiz/review outcomes and mastery changes.
- Metrics clearly distinguish activity from demonstrated mastery.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1102-T01 - Implement analytics read queries | Analytics | 2 d |
| US-1102-T02 - Implement overview endpoints | Core | 1 d |
| US-1102-T03 - Build dashboard UI | Web | 2 d |

#### US-1103 - Invite beta cohort and collect feedback

**User story:** As a **Product owner**, I want to **invite beta cohort and collect feedback** so that I can **validate whether StudyOS closes a real learning loop**.

**Priority:** P1  |  **Story points:** 5  |  **Depends on:** US-1005, US-1101

**Acceptance criteria:**
- At least first invited cohort can onboard without developer database edits.
- Feedback records product goal, distrust points and return intent.
- Critical reliability issues are triaged before increasing cohort size.

**Tasks:**

| Task | Component | Estimate |
|---|---|---:|
| US-1103-T01 - Create beta onboarding checklist | Product | 1 d |
| US-1103-T02 - Add in-product feedback entry points | Web | 2 d |
| US-1103-T03 - Create operator cohort tracking query/dashboard | Analytics | 1 d |
| US-1103-T04 - Run 5-user observation sessions and record issues | Product | 3 d |
| US-1103-T05 - Prioritize fixes using value/risk/effort | Product | 1 d |

## 4. Recommended Critical Path

`E0 -> E1/E2 -> E3 -> E4 -> E6 -> E7 -> E10 production gate` is the minimum technical/product path that proves the source-to-mastery thesis. Language Lab, Tutor and deeper analytics can proceed after the vertical slice is stable.

## 5. Sprint 0 Exit Gate

Before feature implementation begins, Sprint 0 is complete only when: repo builds locally; Docker dependencies are healthy; schema migration runs from empty DB; OpenAPI/AsyncAPI/WS contracts are version controlled; C4/ERD/sequence diagrams are reviewed; ADRs are accepted; CI runs; initial issue backlog is imported.

## 6. Import Files

- `backlog/backlog.csv` - task-level import source for Jira/Linear/GitHub Projects.
- `backlog/backlog.json` - structured hierarchy for custom tooling.
