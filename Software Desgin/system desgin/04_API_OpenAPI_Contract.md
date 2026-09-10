# StudyOS Phase 0 - REST & Internal OpenAPI Contract

## 1. Contract Strategy
StudyOS uses contract-first APIs. The browser-facing Core API and the internal AI API have separate OpenAPI specifications because they serve different trust boundaries and release semantics.

Deliverables:
- `contracts/openapi-core.yaml` - browser/public application REST contract.
- `contracts/openapi-ai-internal.yaml` - service-to-service Core -> FastAPI contract.

The WebSocket protocol is specified separately because OpenAPI does not model the long-lived bidirectional stream adequately for this design.

## 2. API Versioning
Public base path: `/api/v1`.
Internal base path: `/internal/v1`.

Rules:
- additive optional response fields do not require a new major API version;
- renaming/removing fields or changing semantics requires migration/deprecation;
- event contracts version in the routing key/name (`*.v1`);
- internal AI contract still follows explicit versioning even though it is not public.

## 3. Authentication and Authorization
Browser-facing API uses bearer access tokens. Refresh tokens are rotated and stored as hashes server-side. OAuth sign-in resolves to the same internal user/session model.

Authorization is never inferred from object IDs alone. Each request resolving a tenant-owned resource must prove the current user is authorized for the resource's workspace.

Internal AI API uses a separate service credential/JWT with audience restrictions. The payload additionally carries explicit tenant context; this context narrows data access but does not replace service authentication.

## 4. API Conventions
- JSON field names: `camelCase`.
- IDs: UUID strings.
- Time: ISO-8601 UTC timestamps.
- Large collections: cursor pagination.
- Deletes: `204` for synchronous metadata deletion; `202` where derived/object cleanup is asynchronous.
- Async creation: return a `Job` or resource with processing state.
- Errors: stable machine-readable `code`, human-readable `message`, `traceId`, optional `details`.

Canonical error:
```json
{
  "code": "SOURCE_NOT_READY",
  "message": "Source is still processing.",
  "traceId": "4df4683339c44be9a31b036ce57d9e3f",
  "details": {
    "sourceId": "...",
    "currentStage": "EMBEDDING"
  }
}
```

## 5. Idempotency
Endpoints that may be retried across network failures should support an `Idempotency-Key` request header in implementation even where the baseline YAML keeps the schema concise.

Required idempotent operations:
- upload complete;
- retry source;
- artifact generation;
- quiz attempt complete;
- flashcard review submission;
- any payment operation later.

For WebSocket chat, `requestId` is the idempotency key.

## 6. Core Endpoint Catalog
The generated OpenAPI currently defines 46 paths. The following matrix groups them by capability.

### 6.1 Auth
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/auth/register` | Create account |
| POST | `/api/v1/auth/login` | Email/password sign in |
| POST | `/api/v1/auth/refresh` | Rotate refresh token |
| POST | `/api/v1/auth/logout` | Revoke refresh session |
| GET | `/api/v1/me` | Current user |

### 6.2 Workspace & Notebook
| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/api/v1/workspaces` | List/create workspace |
| GET/PATCH/DELETE | `/api/v1/workspaces/{workspaceId}` | Manage workspace |
| GET | `/api/v1/workspaces/{workspaceId}/members` | Membership view |
| GET/POST | `/api/v1/workspaces/{workspaceId}/notebooks` | List/create notebook |
| GET/PATCH/DELETE | `/api/v1/notebooks/{notebookId}` | Manage notebook |

### 6.3 Source Ingestion
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/notebooks/{notebookId}/sources` | List sources |
| POST | `/api/v1/notebooks/{notebookId}/sources/upload-init` | Validate metadata and issue presigned upload |
| POST | `/api/v1/notebooks/{notebookId}/sources/upload-complete` | Confirm object, queue processing |
| POST | `/api/v1/notebooks/{notebookId}/sources/url` | Add WEB/YOUTUBE source |
| POST | `/api/v1/notebooks/{notebookId}/sources/raw` | Add raw text |
| GET | `/api/v1/sources/{sourceId}` | Source metadata |
| GET | `/api/v1/sources/{sourceId}/status` | Detailed processing status |
| POST | `/api/v1/sources/{sourceId}/retry` | Retry a retryable failure |
| DELETE | `/api/v1/sources/{sourceId}` | Async source/dependency deletion |
| GET | `/api/v1/sources/{sourceId}/transcript` | Timestamped transcript |

### 6.4 Conversation
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/notebooks/{notebookId}/conversations` | Create conversation/mode |
| GET | `/api/v1/conversations/{conversationId}` | Conversation metadata |
| GET | `/api/v1/conversations/{conversationId}/messages` | Durable message history |
| POST | `/api/v1/conversations/{conversationId}/ws-token` | Short-lived socket token |
| POST | `/api/v1/messages/{messageId}/feedback` | Quality feedback |

Actual AI generation happens over WebSocket after the conversation is created.

### 6.5 Notes
| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/api/v1/notebooks/{notebookId}/notes` | List/create note |
| GET/PATCH/DELETE | `/api/v1/notes/{noteId}` | Note lifecycle |

### 6.6 Studio/Artifacts
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/notebooks/{notebookId}/artifacts/quiz` | Async quiz generation |
| POST | `/api/v1/notebooks/{notebookId}/artifacts/flashcards` | Async flashcard generation |
| POST | `/api/v1/notebooks/{notebookId}/artifacts/study-guide` | Async study guide generation |
| GET | `/api/v1/jobs/{jobId}` | Async job status |

### 6.7 Quiz
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/quizzes/{quizId}` | Get quiz content |
| POST | `/api/v1/quizzes/{quizId}/attempts` | Start attempt |
| POST | `/api/v1/attempts/{attemptId}/answers` | Save/replace answer |
| POST | `/api/v1/attempts/{attemptId}/complete` | Finalize score + mastery evidence |
| GET | `/api/v1/attempts/{attemptId}` | Result |

### 6.8 Review
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/review/queue` | Due cards for current user |
| POST | `/api/v1/flashcards/{cardId}/reviews` | Grade recall and schedule next review |

### 6.9 Learning
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/notebooks/{notebookId}/concepts` | Concept nodes/relationships projection |
| GET | `/api/v1/notebooks/{notebookId}/mastery` | Current mastery state |
| GET | `/api/v1/learning/recommendations` | Explainable next actions |
| GET/POST | `/api/v1/learning/goals` | Goal lifecycle |
| PATCH | `/api/v1/learning/goals/{goalId}` | Update/pause/complete goal |

### 6.10 Language Lab
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/language/analyze` | Translate/analyze sentence/segment |
| GET/POST | `/api/v1/vocabulary/items` | Vocabulary library |

### 6.11 Analytics
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/analytics/overview` | User learning overview |
| GET | `/api/v1/analytics/notebooks/{notebookId}` | Notebook metrics |

## 7. Source Upload Contract
Recommended flow:

### Step 1 - Initialize
`POST /api/v1/notebooks/{notebookId}/sources/upload-init`

Request:
```json
{
  "fileName": "ddia.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 42188910,
  "checksumSha256": null
}
```

Server actions:
- authorize notebook;
- validate plan file limits/MIME;
- create Source + SourceVersion in `UPLOADING`;
- issue short-lived object upload URL.

### Step 2 - Browser uploads object
Browser PUTs bytes to object storage. This does not mean the source is ready.

### Step 3 - Complete
`POST /api/v1/notebooks/{notebookId}/sources/upload-complete`

Server verifies metadata/object existence where configured, transitions to `QUEUED` and inserts outbox event atomically.

## 8. Async Job Contract
Long-running artifact generation returns `202 Accepted` with:
```json
{
  "id": "job-uuid",
  "type": "QUIZ",
  "status": "PENDING",
  "progress": 0,
  "resultRef": null,
  "errorCode": null
}
```

Client may poll `/jobs/{jobId}` initially. Later, product event push can update jobs over WebSocket without changing job semantics.

## 9. Internal AI API
The AI service is **not** browser-facing.

### 9.1 `/internal/v1/chat/stream`
Input includes:
- signed `InternalContext`;
- conversation/message IDs;
- user query;
- mode;
- conversation summary;
- learner/mastery context.

Output is streaming NDJSON/SSE-like internal events consumed by Spring Core and translated to the public WebSocket envelope.

### 9.2 `/internal/v1/retrieval/debug`
Only enabled for trusted environments/roles. Returns ranked chunks, scores, fusion/rerank metadata and config versions for evaluation.

### 9.3 `/internal/v1/artifacts/generate`
Generates structured quiz/flashcard/study-guide payloads. Core/worker validates and persists them; AI service does not own quiz lifecycle.

### 9.4 `/internal/v1/language/analyze`
Returns structured translation, vocabulary and grammar analysis.

## 10. Internal Context Contract
Representative shape:
```json
{
  "userId": "uuid",
  "workspaceId": "uuid",
  "notebookId": "uuid",
  "sourceIds": ["uuid"],
  "traceId": "trace-id",
  "permissions": ["NOTEBOOK_READ", "CHAT_USE_AI"]
}
```

Rules:
- generated by Core, never accepted directly from browser;
- source IDs must belong to the notebook/workspace;
- AI retrieval always adds workspace/notebook constraints even when source list is empty;
- service token `aud` must match AI service.

## 11. Pagination
For conversations/messages, notes, sources and admin logs when large:
```text
GET /resource?limit=50&cursor=<opaque>
```

Cursor is opaque to client and typically encodes stable sort key + ID. Do not expose raw SQL offsets for high-volume append-only streams.

## 12. Conditional Requests and Concurrency
For editable resources like notes/notebooks, add version/ETag later if concurrent editing becomes real. For Phase 0 single-user/personal workspace flows, database `updated_at` and server-side last-write rules are acceptable.

For mastery and scheduler state, concurrency control is mandatory from the start because retries/background actions can race.

## 13. Validation Boundaries
Validate at three levels:
1. transport DTO/Pydantic/Bean Validation - shape, length, basic ranges;
2. application command - authorization and use-case preconditions;
3. domain model - invariants and legal state transitions.

Do not rely only on frontend validation.

## 14. Error Code Taxonomy
Suggested stable codes:
```text
AUTH_INVALID_CREDENTIALS
AUTH_REFRESH_REVOKED
WORKSPACE_FORBIDDEN
NOTEBOOK_NOT_FOUND
SOURCE_FILE_TOO_LARGE
SOURCE_UNSUPPORTED_TYPE
SOURCE_NOT_READY
SOURCE_NOT_RETRYABLE
SOURCE_PROCESSING_FAILED
CHAT_GENERATION_IN_PROGRESS
CHAT_INSUFFICIENT_EVIDENCE
AI_PROVIDER_UNAVAILABLE
QUIZ_ATTEMPT_ALREADY_COMPLETED
REVIEW_ALREADY_RECORDED
RATE_LIMITED
QUOTA_EXCEEDED
VALIDATION_FAILED
```

## 15. OpenAPI CI Rules
CI must at minimum:
- parse both YAML files;
- fail on duplicate operation IDs or unresolved local refs using chosen validator/tooling;
- generate client/server types where adopted;
- run contract tests comparing selected controller responses to schema;
- block undocumented breaking changes after beta contract freeze.

## 16. What Is Deliberately Not in the Public API Yet
- billing/payment;
- organization/teacher administration;
- voice streaming;
- GitHub repository ingestion;
- agentic web research;
- public developer API.

These are versioned future capabilities, not hidden assumptions in MVP endpoints.

