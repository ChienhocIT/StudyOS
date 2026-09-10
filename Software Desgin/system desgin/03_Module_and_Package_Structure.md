# StudyOS Phase 0 - Module & Package Structure

## 1. Repository Strategy
Use a monorepo initially because one developer/small team owns the product and contract changes across frontend, Core API, AI service and workers are frequent.

```text
studyos/
├── apps/
│   ├── web/                     # Next.js + TypeScript
│   ├── core-api/                # Spring Boot modular monolith
│   ├── ai-service/              # FastAPI internal AI API
│   └── worker/                  # RabbitMQ worker entry points
├── packages/
│   ├── contracts/               # generated/shared OpenAPI/JSON schemas where practical
│   ├── ui/                      # optional shared web UI package
│   └── python-ai-core/          # shared AI primitives used by ai-service + workers
├── infrastructure/
│   ├── docker/
│   ├── terraform/
│   └── observability/
├── database/
│   └── migrations/
├── docs/
│   ├── adr/
│   ├── architecture/
│   └── runbooks/
├── tests/
│   ├── e2e/
│   ├── load/
│   └── security/
├── .github/workflows/
├── docker-compose.yml
└── README.md
```

## 2. Spring Boot Core API Package Structure
Base package: `com.studyos`.

Each business module follows a local clean/hexagonal structure. Avoid one giant global `controller/service/repository/entity` layout.

```text
apps/core-api/src/main/java/com/studyos/
├── StudyOsApplication.java
├── identity/
│   ├── api/
│   │   ├── AuthController.java
│   │   ├── MeController.java
│   │   └── dto/
│   ├── application/
│   │   ├── AuthApplicationService.java
│   │   ├── command/
│   │   ├── query/
│   │   └── port/
│   ├── domain/
│   │   ├── User.java
│   │   ├── RefreshSession.java
│   │   ├── UserRepository.java
│   │   └── event/
│   └── infrastructure/
│       ├── persistence/
│       ├── oauth/
│       └── security/
├── workspace/
├── notebook/
├── source/
├── conversation/
├── studio/
├── learning/
├── review/
├── language/
├── analytics/
├── admin/
└── shared/
    ├── kernel/
    ├── security/
    ├── persistence/
    ├── outbox/
    ├── messaging/
    ├── observability/
    ├── web/
    └── testing/
```

## 3. Spring Module Responsibilities

### 3.1 `identity`
Owns:
- registration/login/logout;
- password hashing;
- OAuth identity linking;
- access/refresh token lifecycle;
- current user profile.

Does not own workspace roles. Identity proves who the caller is; Workspace decides what the caller may access.

Suggested classes:
```text
identity/api/AuthController
identity/application/AuthApplicationService
identity/application/RefreshTokenService
identity/domain/User
identity/domain/UserIdentity
identity/domain/RefreshSession
identity/domain/UserRepository
identity/infrastructure/persistence/JpaUserRepositoryAdapter
identity/infrastructure/security/JwtTokenService
```

### 3.2 `workspace`
Owns:
- workspace aggregate;
- membership;
- roles and permission checks;
- membership queries used by other modules through a narrow application interface.

Key port:
```java
public interface WorkspaceAuthorization {
    void requireMember(UserId userId, WorkspaceId workspaceId);
    void requireRole(UserId userId, WorkspaceId workspaceId, WorkspaceRole... roles);
}
```

### 3.3 `notebook`
Owns notebook metadata, archival and user-visible learning goal text associated with a notebook. It does not own source processing.

### 3.4 `source`
Owns:
- source aggregate and version lifecycle;
- upload-init/upload-complete;
- checksum and MIME/size validation;
- source processing state machine;
- retry/delete commands;
- source outbox events;
- processing status projection.

Important domain objects:
```text
Source
SourceVersion
SourceProcessingState
SourceType
SourceFailure
SourceRepository
ObjectStoragePort
SourceEventPublisher (implemented via outbox)
```

### 3.5 `conversation`
Owns:
- conversation aggregate;
- message persistence and sequence numbers;
- WebSocket session authorization;
- short-lived WS token issuance;
- AI stream orchestration from the Core side;
- final citation persistence;
- user feedback.

It does **not** implement vector retrieval or prompt logic.

### 3.6 `studio`
Owns async learning artifact jobs and durable generated quiz/flashcard/study-guide aggregates. Generation is delegated to workers/AI service; Core validates and persists resulting artifacts.

### 3.7 `learning`
Owns:
- concepts as product-visible learning entities;
- concept relations accepted into the notebook;
- mastery evidence;
- user mastery state;
- learning goals;
- recommendations.

Recommended application services:
```text
MasteryEvidenceService
MasteryProjectionService
LearningRecommendationService
ConceptApplicationService
LearningGoalApplicationService
```

### 3.8 `review`
Owns flashcard scheduling and review lifecycle. Scheduler implementation is a domain service with deterministic tests and no LLM dependency.

```java
public interface ReviewScheduler {
    ReviewScheduleResult schedule(CardScheduleState previous, ReviewGrade grade, Instant reviewedAt);
}
```

### 3.9 `language`
Owns saved vocabulary and user-facing language learning actions. It calls AI Service for sentence analysis but persists vocabulary and card creation through Core modules.

### 3.10 `analytics`
Owns product/learning read models and aggregation queries. It should not become a dumping ground for domain logic. Its data is derived from durable operational tables and emitted events.

### 3.11 `admin`
Post-beta operations endpoints: source failures, queue summaries, AI usage/cost and user support actions. All admin actions are audited.

## 4. Shared Package Rules
`shared` contains technical cross-cutting capabilities, not generic business entities.

Allowed:
- `TenantContext`;
- `ApiError`;
- `Clock` abstraction;
- tracing helpers;
- outbox infrastructure;
- pagination types;
- JSON/object mapper configuration;
- security filters.

Avoid:
- `CommonService`;
- `BaseEntity` with unrelated domain behavior;
- cross-module DTO dumping;
- globally shared JPA repositories.

## 5. Dependency Enforcement
Use ArchUnit tests to fail the build when boundaries are violated.

Example rules:
```text
..domain.. may depend only on java.*, approved shared kernel types and same module domain.
..application.. may depend on same module domain and application ports.
..api.. may depend on application, never persistence adapters.
..infrastructure.. may implement ports and depend on frameworks.
One module's infrastructure package may not be imported by another module.
```

Cross-module calls should target explicit public application interfaces such as:
```text
WorkspaceAuthorization
NotebookLookup
LearningEvidenceGateway
ReviewCardCreation
```

## 6. Suggested Core API Layer Example - Source Module

```text
source/
├── api/
│   ├── SourceController.java
│   ├── SourceStatusController.java
│   └── dto/
│       ├── UploadInitRequest.java
│       ├── UploadInitResponse.java
│       └── SourceResponse.java
├── application/
│   ├── SourceApplicationService.java
│   ├── SourceStateTransitionService.java
│   ├── command/
│   │   ├── InitializeUpload.java
│   │   ├── CompleteUpload.java
│   │   ├── CreateUrlSource.java
│   │   └── RetrySource.java
│   ├── query/
│   │   └── SourceQueryService.java
│   └── port/
│       ├── ObjectStoragePort.java
│       └── SourceProcessingPublisher.java
├── domain/
│   ├── Source.java
│   ├── SourceVersion.java
│   ├── SourceStatus.java
│   ├── SourceType.java
│   ├── SourceRepository.java
│   ├── SourcePolicy.java
│   └── event/
└── infrastructure/
    ├── persistence/
    ├── storage/
    ├── messaging/
    └── config/
```

## 7. FastAPI AI Service Structure

```text
apps/ai-service/
├── app/
│   ├── main.py
│   ├── api/
│   │   ├── dependencies.py
│   │   └── v1/
│   │       ├── chat.py
│   │       ├── retrieval.py
│   │       ├── artifacts.py
│   │       ├── language.py
│   │       └── health.py
│   ├── core/
│   │   ├── config.py
│   │   ├── logging.py
│   │   ├── security.py
│   │   ├── exceptions.py
│   │   └── telemetry.py
│   ├── contracts/
│   │   ├── internal_context.py
│   │   ├── chat.py
│   │   ├── citations.py
│   │   └── artifacts.py
│   ├── retrieval/
│   │   ├── query_normalizer.py
│   │   ├── vector_search.py
│   │   ├── lexical_search.py
│   │   ├── fusion.py
│   │   ├── reranker.py
│   │   ├── context_builder.py
│   │   └── citation_validator.py
│   ├── generation/
│   │   ├── grounded_answer.py
│   │   ├── stream_events.py
│   │   └── structured_output.py
│   ├── tutor/
│   │   ├── service.py
│   │   ├── socratic.py
│   │   ├── feynman.py
│   │   ├── exam.py
│   │   └── state.py
│   ├── artifacts/
│   │   ├── quiz_generator.py
│   │   ├── flashcard_generator.py
│   │   └── study_guide_generator.py
│   ├── language/
│   │   ├── analyzer.py
│   │   └── schemas.py
│   ├── model_gateway/
│   │   ├── base.py
│   │   ├── router.py
│   │   ├── providers/
│   │   └── usage.py
│   ├── prompts/
│   │   ├── registry.py
│   │   └── versions/
│   ├── repositories/
│   │   ├── chunks.py
│   │   ├── ai_runs.py
│   │   └── configs.py
│   └── evaluation/
│       ├── datasets.py
│       ├── retrieval_metrics.py
│       └── answer_metrics.py
├── tests/
│   ├── unit/
│   ├── integration/
│   ├── eval/
│   └── security/
├── pyproject.toml
└── Dockerfile
```

## 8. AI Service Dependency Rules
- API routes validate request shape and internal auth, then call application components.
- Retrieval code never trusts `sourceIds` without tenant context.
- Provider SDKs appear only under `model_gateway/providers`.
- Prompt templates are versioned and loaded through registry; no large inline prompts scattered through routes.
- Citation validator is mandatory for grounded mode.
- AI code may propose structured results but cannot directly bypass Core business rules.
- Evaluation code is reusable in CI/offline scripts and not embedded only in notebooks.

## 9. Worker Package Structure
Use one worker application with independently selectable consumer groups at first.

```text
apps/worker/
├── worker/
│   ├── main.py
│   ├── messaging/
│   │   ├── connection.py
│   │   ├── consumer.py
│   │   ├── publisher.py
│   │   └── idempotency.py
│   ├── consumers/
│   │   ├── parse_source.py
│   │   ├── fetch_transcript.py
│   │   ├── index_source.py
│   │   ├── enrich_source.py
│   │   └── generate_artifact.py
│   ├── ingestion/
│   │   ├── parsers/
│   │   ├── normalization/
│   │   ├── chunking/
│   │   └── transcript/
│   └── adapters/
│       ├── postgres.py
│       ├── object_storage.py
│       └── ai_client.py
└── tests/
```

Deployment can select consumers with environment variables such as:
```text
WORKER_QUEUES=q.source.parse.v1,q.source.transcript.v1
```

Later split Docker services by workload without changing package ownership.

## 10. Frontend Structure
Although not the core of Phase 0, frontend boundaries should match backend concepts.

```text
apps/web/src/
├── app/
├── features/
│   ├── auth/
│   ├── workspace/
│   ├── notebook/
│   ├── sources/
│   ├── chat/
│   ├── notes/
│   ├── quiz/
│   ├── review/
│   ├── learning/
│   └── language-lab/
├── entities/
├── shared/
│   ├── api/
│   ├── ws/
│   ├── ui/
│   └── lib/
└── tests/
```

Generate REST client types from OpenAPI where practical. WebSocket events use a generated/validated schema from the protocol contract.

## 11. Configuration Separation
Common environment groups:

### Core API
```text
DATABASE_URL
REDIS_URL
RABBITMQ_URL
JWT_ISSUER
JWT_SIGNING_KEY / key reference
AI_INTERNAL_URL
AI_SERVICE_AUDIENCE
S3_BUCKET
S3_REGION
OTEL_EXPORTER_OTLP_ENDPOINT
```

### AI Service
```text
DATABASE_READ_URL
MODEL_PROVIDER_* credentials
EMBEDDING_MODEL
RERANKER_MODEL
PROMPT_REGISTRY_PATH
INTERNAL_JWT_AUDIENCE
OTEL_EXPORTER_OTLP_ENDPOINT
```

### Worker
```text
RABBITMQ_URL
DATABASE_DERIVED_DATA_URL
S3_BUCKET
WORKER_QUEUES
CONCURRENCY
MAX_ATTEMPTS
AI_INTERNAL_URL
```

Secrets are injected at runtime and never committed.

## 12. Testing Package Strategy

### Core
```text
unit: domain rules, state machines, scheduler, mastery updates
slice: controller/serialization/security slices
integration: Testcontainers PostgreSQL/RabbitMQ/MinIO-compatible integration
architecture: ArchUnit
contract: OpenAPI response/request compatibility
```

### AI
```text
unit: fusion, context budgeting, citation validation, provider routing
integration: pgvector retrieval and model-provider fake server
security: cross-tenant filters and indirect prompt injection corpus
eval: versioned retrieval/answer datasets
```

### E2E
Playwright covers the production-critical vertical slice: register -> notebook -> source -> READY -> chat/citation -> quiz -> mastery -> review.

## 13. Coding Conventions to Freeze
- Java 21 baseline unless project environment requires another LTS.
- Python 3.12 baseline unless a dependency requires otherwise.
- TypeScript strict mode.
- UUID IDs serialized as strings.
- API JSON camelCase; database snake_case; Python internal schemas snake_case or explicit aliases consistently.
- UTC timestamps in storage/API; user timezone applied only for presentation/scheduling decisions.
- Money/cost values use decimal, never binary float for persisted billing amounts.
- No domain decision based on client-provided role/workspace ownership claims.

## 14. Definition of an Implemented Module
A module is not considered complete until it has:
- public application interface;
- authorization rule;
- domain invariants;
- persistence adapter;
- API/event contract where applicable;
- tests including negative/unauthorized paths;
- tracing/metrics;
- migration/index review;
- no forbidden dependency violations.

