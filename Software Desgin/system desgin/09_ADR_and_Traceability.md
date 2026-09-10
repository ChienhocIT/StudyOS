# StudyOS Phase 0 - Architecture Decision Records & Traceability

## 1. ADR Register

| ADR | Decision | Status | Revisit trigger |
|---|---|---|---|
| ADR-001 | Spring Boot Core starts as modular monolith | Accepted | team/scale/availability evidence requires split |
| ADR-002 | FastAPI is a separate AI process boundary | Accepted | AI workload becomes trivial or another runtime is demonstrably better |
| ADR-003 | PostgreSQL + pgvector + PostgreSQL FTS is initial retrieval store | Accepted | measured retrieval scale/latency/ops bottleneck |
| ADR-004 | RabbitMQ + transactional outbox for durable async workflows | Accepted | workload semantics require another log/stream model |
| ADR-005 | Browser WebSocket terminates at Core API | Accepted | dedicated real-time gateway is justified by scale |
| ADR-006 | Mastery is evidence-based and rules-first | Accepted | calibrated data supports a stronger statistical model |
| ADR-007 | External/retrieved content is untrusted AI input | Accepted | never removed; controls may strengthen |
| ADR-008 | Presigned object storage for uploads | Accepted | product requires server-side transform-on-upload |
| ADR-009 | Workspace is the primary tenant boundary | Accepted | organization hierarchy becomes required |
| ADR-010 | Public/internal/event/WS contracts are explicitly versioned | Accepted | never removed; version policy may evolve |

## 2. ADR-001 - Modular Monolith Before Microservices
**Context:** One developer/small team needs strong domain boundaries without operating many distributed services.

**Decision:** Keep transactional business capabilities in one Spring Boot deployable, split internally by module. AI and workers remain separate because their runtime, dependencies and scaling are materially different.

**Consequences:**
- easier transactions and local debugging;
- fewer deployment/network failure modes;
- architecture discipline must be enforced with package rules/ArchUnit;
- later service extraction requires explicit API/data ownership migration.

## 3. ADR-002 - Spring Core + FastAPI AI Boundary
**Context:** Core business behavior benefits from Java/Spring ecosystem; AI libraries and LLM orchestration evolve primarily in Python.

**Decision:** Core owns authentication, tenancy and business state. FastAPI owns AI computation/retrieval. Communication is internal authenticated HTTP streaming for chat and task calls for structured AI work.

**Consequence:** Avoid sharing business authorization logic in Python. Share contracts, not business repositories.

## 4. ADR-003 - PostgreSQL/pgvector Before Dedicated Vector DB
**Decision:** Use PostgreSQL for relational truth, FTS and vector search initially.

**Rationale:** Simplifies tenant filtering, backup, local development and operational footprint. A separate vector store is introduced only after a benchmark demonstrates a real limitation.

## 5. ADR-004 - RabbitMQ + Transactional Outbox
**Decision:** Use RabbitMQ for long-running asynchronous tasks and outbox for atomic business-change-to-message publication.

**Consequences:** At-least-once delivery, idempotent consumers, retry/DLQ operations and event versioning are mandatory.

## 6. ADR-005 - Core-Owned WebSocket Boundary
**Decision:** Client WebSocket terminates in Spring Core. FastAPI streams internally to Core.

**Rationale:** Authentication, quota enforcement, conversation idempotency and persistence stay in one public security boundary.

**Trade-off:** Core performs stream proxying. If chat scale dominates later, extract a real-time gateway while preserving the protocol.

## 7. ADR-006 - Evidence-Based Mastery
**Decision:** Mastery changes only from evidence-producing learner actions. Score and confidence are separate. Begin with a transparent deterministic model.

**Rejected alternative:** Ask an LLM to assign mastery from conversation alone. Reason: poor calibration, hard to reproduce/audit and too easy to inflate.

## 8. ADR-007 - Retrieved Content Is Untrusted
**Decision:** PDF/web/transcript text is data, never authority. It cannot change system policy, grant tool permission or access other tenants.

**Implementation implications:** instruction/data delimiter, tool allowlist, authorization before retrieval, output validation and adversarial tests.

## 9. ADR-008 - Presigned Uploads
**Decision:** Core initializes and authorizes upload; browser uploads directly to object storage; Core verifies completion and queues processing.

**Benefits:** avoids large file bytes tying up app servers and scales storage bandwidth independently.

## 10. ADR-009 - Workspace Tenant Boundary
All primary resources resolve to one workspace. Personal users receive a personal workspace. Future team/education plans reuse the same boundary rather than redesigning authorization.

## 11. ADR-010 - Contract Versioning
- REST: `/api/v1` and `/internal/v1`.
- WebSocket: `protocolVersion: 1.0`.
- Events: name/routing key includes `.v1`.
- Prompt/retrieval configurations have independent version labels for evaluation.

## 12. Critical Requirement Traceability Matrix

| Product capability | Core module | Main tables | REST/WS contract | RabbitMQ | Primary stories |
|---|---|---|---|---|---|
| Account/session | Identity | users, identities, refresh_tokens | `/auth/*`, `/me` | - | US-0101, US-0102 |
| Tenant isolation | Workspace | workspaces, workspace_members | workspace/notebook paths | all events carry workspaceId | US-0103 |
| Notebook | Notebook | notebooks | `/workspaces/{id}/notebooks`, `/notebooks/{id}` | - | US-0201 |
| File source | Source | sources, versions, jobs | upload-init/complete/status | source.parse.requested + source events | US-0301..0305 |
| Retrieval | AI service | document_chunks | internal retrieval/chat | source.indexed informs readiness | US-0401 |
| Grounded citation | Conversation + AI | messages, citations, chunks | WebSocket + messages | - | US-0402..0404 |
| Quiz | Studio/Quiz | artifact_jobs, quizzes, attempts | artifact + quiz paths | quiz.generate / quiz.generated / attempt.completed | US-0601, US-0602 |
| Flashcard/review | Review | decks, cards, reviews | review queue / grade | flashcard.reviewed | US-0603, US-0604 |
| Mastery | Learning | mastery_evidence, user_concept_mastery | `/mastery` | mastery.updated | US-0701..0703 |
| Recommendation | Learning | goals, recommendations | `/learning/recommendations` | learning facts as triggers | US-0704 |
| Language Lab | Language/Source | transcript_segments, vocabulary_items | transcript/language/vocabulary | transcript events | US-0801..0804 |
| Production ops | Shared/Analytics | ai_runs, audit, outbox, processed_events | admin/ops later | all queues | US-1001..1006 |

## 13. Phase 0 Freeze Checklist
A design item is considered frozen enough to implement when all are true:
- owner/module is known;
- persistent data owner is known;
- public/internal/event contract is defined where relevant;
- transaction and consistency expectation is stated;
- authorization scope is stated;
- idempotency behavior is stated for retryable operations;
- failure path is defined;
- telemetry/trace identifiers are defined;
- backlog story/task exists.

If implementation reveals an invalid assumption, update the ADR and contract first, then code. Phase 0 is a baseline, not a prohibition on learning.

