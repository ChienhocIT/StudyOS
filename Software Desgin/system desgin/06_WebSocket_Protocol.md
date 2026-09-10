# StudyOS Phase 0 - WebSocket Protocol

## 1. Purpose
The public WebSocket is the real-time transport for AI conversations. It provides token streaming, citations, tool/status events, cancellation and reconnect/resume while keeping authorization and durable conversation state in Spring Core.

Endpoint baseline:
```text
wss://api.studyos.example/ws/v1/conversations/{conversationId}?token=<short-lived-ws-token>
```

The token is obtained from:
`POST /api/v1/conversations/{conversationId}/ws-token`.

Do not place a long-lived refresh token in the WebSocket URL.

## 2. Connection Lifecycle

```text
DISCONNECTED
  -> CONNECTING
  -> AUTHENTICATING
  -> READY
  -> GENERATING (optional)
  -> READY
  -> RECONNECTING on transient failure
  -> CLOSED on explicit logout/forbidden/protocol failure
```

The server validates:
- token signature/expiry/audience;
- token conversation ID;
- current user still has notebook/workspace access;
- conversation is not deleted/forbidden.

## 3. Protocol Envelope
All client/server frames are JSON text frames using:

```json
{
  "type": "assistant.delta",
  "protocolVersion": "1.0",
  "eventId": "uuid",
  "timestamp": "2026-09-10T14:00:01Z",
  "traceId": "trace-id-or-null",
  "requestId": "uuid-or-null",
  "conversationId": "uuid",
  "messageId": "uuid-or-null",
  "sequence": 6,
  "payload": {}
}
```

Machine-readable schema: `contracts/websocket-protocol.schema.json`.

## 4. Client Commands

### 4.1 `chat.message.send`
Starts one user turn.

```json
{
  "type": "chat.message.send",
  "protocolVersion": "1.0",
  "eventId": "uuid",
  "timestamp": "...",
  "traceId": null,
  "requestId": "client-generated-uuid",
  "conversationId": "uuid",
  "messageId": null,
  "sequence": null,
  "payload": {
    "content": "Explain database isolation levels using my sources.",
    "mode": "ASK",
    "sourceIds": [],
    "clientContext": {
      "activeSourceId": null
    }
  }
}
```

Rules:
- `requestId` is mandatory and idempotent;
- server validates all `sourceIds` belong to current notebook;
- `content` has a configured maximum length;
- only one active generation per conversation in MVP;
- duplicate `requestId` must not create a second user message/AI run.

### 4.2 `chat.generation.cancel`
```json
{
  "type": "chat.generation.cancel",
  "protocolVersion": "1.0",
  "eventId": "uuid",
  "timestamp": "...",
  "requestId": "original-request-id",
  "conversationId": "uuid",
  "messageId": "assistant-message-id",
  "sequence": null,
  "payload": {}
}
```

Server performs best-effort cancellation upstream. Final durable message status becomes `CANCELLED` if cancellation wins the race.

### 4.3 `chat.resume`
Sent after reconnect.

```json
{
  "type": "chat.resume",
  "protocolVersion": "1.0",
  "eventId": "uuid",
  "timestamp": "...",
  "requestId": null,
  "conversationId": "uuid",
  "messageId": null,
  "sequence": null,
  "payload": {
    "lastReceivedSequence": 18
  }
}
```

Server replays available buffered events with sequence > 18. If replay buffer expired, server sends durable current message state or an explicit `RESUME_BUFFER_EXPIRED` error so the client refreshes REST history.

### 4.4 `ping`
Application heartbeat when browser/proxy behavior requires it.

## 5. Server Events

### 5.1 `session.ready`
Sent once after authorization.
Payload:
```json
{
  "serverTime": "...",
  "heartbeatSeconds": 25,
  "maxFrameBytes": 16384,
  "activeGeneration": null
}
```

### 5.2 `assistant.started`
Provides the assistant `messageId`, trace ID and initial sequence.

### 5.3 `retrieval.completed`
Safe metadata only; not necessarily shown in normal UI.
```json
{
  "candidateCount": 24,
  "contextCount": 7,
  "retrievalConfigVersion": "rag-v1"
}
```
Do not expose hidden prompts, raw authorization internals or unrelated source content.

### 5.4 `assistant.delta`
Incremental text:
```json
{ "delta": "Read Committed prevents dirty reads" }
```

### 5.5 `citation.provisional`
Allows UI to render source chips during streaming. It is provisional until final validation.
```json
{
  "key": "C2",
  "sourceId": "uuid",
  "sourceTitle": "DDIA.pdf",
  "pageNo": 153,
  "startMs": null,
  "endMs": null
}
```

### 5.6 `tool.started` / `tool.completed`
Used only for approved tutor/research tools. Payload reveals user-useful status, not secret arguments.

### 5.7 `assistant.completed`
Canonical end-of-generation event.
```json
{
  "status": "COMPLETED",
  "groundingStatus": "SUPPORTED",
  "citationIds": ["uuid"],
  "usage": {
    "inputTokens": 2010,
    "outputTokens": 422,
    "estimatedCostUsd": 0.0041
  }
}
```

### 5.8 `assistant.failed`
```json
{
  "code": "AI_PROVIDER_UNAVAILABLE",
  "message": "The AI provider is temporarily unavailable.",
  "retryable": true
}
```

### 5.9 `usage.updated`
Optional quota/usage update after completion.

### 5.10 `error`
Protocol/session error unrelated to one completed assistant message.

## 6. Sequence and Ordering
`sequence` is monotonically increasing per conversation WebSocket stream, allocated server-side. Client:
- discards duplicate `eventId`;
- applies events in sequence order;
- detects gaps and requests resume/REST refresh;
- never invents a completed answer solely from deltas.

Durable `messages.sequence_no` is related to message ordering, while WebSocket event `sequence` orders transport events. Keep these concepts separate in implementation.

## 7. Reconnect Strategy
Client backoff example:
```text
0.5s -> 1s -> 2s -> 5s -> 10s -> max 30s with jitter
```

On reconnect:
1. obtain a fresh WS token if previous token expired;
2. reconnect;
3. receive `session.ready`;
4. send `chat.resume(lastReceivedSequence)`;
5. if buffer unavailable, GET durable messages.

Do not resend `chat.message.send` blindly without its original `requestId`.

## 8. Replay Buffer
Redis stores a short event buffer per active conversation/generation, e.g. 5-10 minutes or a capped event count. This is a UX optimization, not durable history.

Key concept:
```text
ws:conversation:{conversationId}:events
```

Use TTL and bounded length. Durable completed messages remain in PostgreSQL.

## 9. Heartbeat
- server advertises heartbeat interval, baseline 25 seconds;
- client sends `ping` when no traffic for the configured period;
- server responds `pong`;
- stale connections are closed after a configured grace period.

## 10. Backpressure
MVP rules:
- maximum frame size ~16 KB;
- batch tiny token deltas into sensible chunks rather than one frame per token;
- one active generation per conversation;
- cap concurrent active generations per user/account plan;
- if outbound socket buffer grows too large, cancel/close generation rather than unbounded memory growth.

## 11. Rate Limits
Apply server-side limits to:
- connection attempts/IP/user;
- concurrent connections per user;
- `chat.message.send` rate;
- active generations;
- daily/monthly AI quota.

Example close/error behavior should state whether the client may retry and when.

## 12. Close Codes
Use standard close codes where appropriate plus application-specific 4xxx codes.

Suggested:
| Code | Meaning |
|---:|---|
| 1000 | Normal closure |
| 1001 | Server going away/deploy |
| 1009 | Message too large |
| 4001 | WS token invalid/expired |
| 4003 | Conversation/workspace forbidden |
| 4008 | Rate/quota limit |
| 4100 | Unsupported protocol version |
| 4409 | Conflicting active generation |
| 4500 | Internal server error |

## 13. Security
- short-lived WS token scoped to one conversation/user;
- TLS required in production;
- validate Origin for browser clients;
- never authorize by conversation ID alone;
- sanitize/log only safe payload summaries;
- retrieved content cannot instruct the server to invoke unauthorized tools;
- server controls source scope regardless of client claims.

## 14. Persistence Rules
Persist before generation:
- user message;
- `requestId` dedup record via message uniqueness;
- assistant placeholder/status as needed.

Persist on finalization:
- final assistant content/status;
- validated citations;
- grounding status;
- usage/trace references.

Partial deltas may be transient. If a generation fails, durable state records failure/cancel status.

## 15. Test Matrix
Automated tests must cover:
- invalid/expired WS token;
- cross-tenant conversation ID;
- duplicate `requestId`;
- reconnect with available replay;
- reconnect after replay TTL expires;
- out-of-order/duplicate client handling simulation;
- cancellation race;
- provider failure mid-stream;
- message too large;
- rate limiting;
- horizontal node routing using Redis.

