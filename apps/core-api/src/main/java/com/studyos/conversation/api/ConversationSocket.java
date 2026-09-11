package com.studyos.conversation.api;

import com.studyos.conversation.application.ConversationService;
import com.studyos.conversation.application.port.*;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ConversationSocket extends TextWebSocketHandler implements MessageListener {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ConversationSocket.class);
    private final ConversationService service;
    private final StreamEvents events;
    private final ChatStream ai;
    private final Map<String, FutureTask<Void>> generations = new ConcurrentHashMap<>();

    private static String generationKey(UUID conversation, UUID request) {
        return conversation + ":" + request;
    }

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ConversationSocket(ConversationService service, StreamEvents events, ChatStream ai) {
        this.service = service;
        this.events = events;
        this.ai = ai;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID user = user(session);
        long count = sessions.values().stream().filter(s -> user.equals(user(s))).count();
        if (count >= 5) {
            session.close(new CloseStatus(4008, "Too many connections"));
            return;
        }
        session.setTextMessageSizeLimit(16384);
        session.setBinaryMessageSizeLimit(0);
        sessions.put(
                session.getId(), new ConcurrentWebSocketSessionDecorator(session, 10000, 65536));
        direct(
                session,
                "session.ready",
                null,
                null,
                Map.of(
                        "serverTime",
                        Instant.now().toString(),
                        "heartbeatSeconds",
                        25,
                        "maxFrameBytes",
                        16384));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws Exception {
        UUID conversation = conversation(session), user = user(session);
        UUID request = null;
        try {
            var frame = Json.object(message.getPayload());
            if (!"1.0".equals(frame.get("protocolVersion")))
                throw ApiException.badRequest(
                        "UNSUPPORTED_PROTOCOL", "Protocol version 1.0 is required.");
            if (!conversation.toString().equals(Objects.toString(frame.get("conversationId"), "")))
                throw ApiException.forbidden(
                        "CONVERSATION_MISMATCH", "Frame conversation differs from this session.");
            service.get(user, conversation);
            if (frame.get("requestId") != null)
                request = UUID.fromString(frame.get("requestId").toString());
            Map<String, Object> payload =
                    frame.get("payload") instanceof Map<?, ?> p
                            ? (Map<String, Object>) p
                            : Map.of();
            String type = Objects.toString(frame.get("type"), "");
            switch (type) {
                case "ping" -> direct(session, "pong", null, null, Map.of());
                case "chat.resume" -> {
                    long after =
                            ((Number) payload.getOrDefault("lastReceivedSequence", 0)).longValue();
                    if (after < 0)
                        throw ApiException.badRequest(
                                "INVALID_SEQUENCE", "Sequence must be nonnegative.");
                    var replay = events.replay(conversation, after);
                    if (replay.expired())
                        direct(
                                session,
                                "error",
                                null,
                                null,
                                Map.of(
                                        "code",
                                        "RESUME_BUFFER_EXPIRED",
                                        "message",
                                        "Refresh durable conversation history.",
                                        "retryable",
                                        false,
                                        "lastSequence",
                                        replay.lastSequence()));
                    else
                        for (String event : replay.events()) {
                            if (!sendAuthorized(session, event)) break;
                        }
                }
                case "chat.generation.cancel" -> {
                    if (request == null)
                        throw ApiException.badRequest(
                                "REQUEST_ID_REQUIRED", "requestId is required.");
                    if (service.cancel(user, conversation, request)) {
                        var task = generations.remove(generationKey(conversation, request));
                        if (task != null) task.cancel(true);
                        ai.cancel(conversation, request);
                        events.publish(
                                conversation,
                                request,
                                null,
                                "assistant.completed",
                                Map.of(
                                        "status",
                                        "CANCELLED",
                                        "groundingStatus",
                                        "INSUFFICIENT",
                                        "citationIds",
                                        List.of()));
                    }
                }
                case "chat.message.send" -> {
                    if (request == null)
                        throw ApiException.badRequest(
                                "REQUEST_ID_REQUIRED", "requestId is required.");
                    if (!events.allow("chat:" + user, 20, 60))
                        throw new ApiException(
                                429, "RATE_LIMITED", "Wait before sending another message.");
                    List<UUID> selected =
                            payload.get("sourceIds") instanceof List<?> l
                                    ? l.stream().map(v -> UUID.fromString(v.toString())).toList()
                                    : List.of();
                    if (selected.size() > 100)
                        throw ApiException.badRequest(
                                "INVALID_SOURCE_SCOPE", "Select at most 100 sources.");
                    var turn =
                            service.begin(
                                    user,
                                    conversation,
                                    request,
                                    (String) payload.get("content"),
                                    (String) payload.get("mode"),
                                    selected);
                    if (turn.duplicate()) {
                        direct(
                                session,
                                "error",
                                request,
                                turn.messageId(),
                                Map.of(
                                        "code",
                                        "REQUEST_ALREADY_ACCEPTED",
                                        "message",
                                        "This request is already in conversation history.",
                                        "retryable",
                                        false));
                    } else {
                        String key = generationKey(turn.conversationId(), turn.requestId());
                        var task =
                                new FutureTask<Void>(
                                        () -> {
                                            try {
                                                generate(turn);
                                            } finally {
                                                generations.remove(key);
                                            }
                                            return null;
                                        });
                        generations.put(key, task);
                        // Cancellation can commit between begin() and task registration.
                        if (!service.active(turn.conversationId(), turn.requestId())) {
                            generations.remove(key, task);
                            task.cancel(true);
                        } else executor.execute(task);
                    }
                }
                default ->
                        throw ApiException.badRequest(
                                "UNKNOWN_COMMAND", "Unsupported WebSocket command.");
            }
        } catch (ApiException e) {
            direct(
                    session,
                    "error",
                    request,
                    null,
                    Map.of(
                            "code",
                            e.code(),
                            "message",
                            e.getMessage(),
                            "retryable",
                            e.status() == 429));
        } catch (Exception e) {
            direct(
                    session,
                    "error",
                    request,
                    null,
                    Map.of(
                            "code",
                            "INVALID_FRAME",
                            "message",
                            "The frame could not be processed.",
                            "retryable",
                            false));
        }
    }

    @SuppressWarnings("unchecked")
    private void generate(ConversationService.Turn turn) {
        StringBuilder partial = new StringBuilder();
        boolean[] completed = {false};
        try {
            if (Thread.currentThread().isInterrupted()
                    || !service.active(turn.conversationId(), turn.requestId())) return;
            events.publish(
                    turn.conversationId(),
                    turn.requestId(),
                    turn.messageId(),
                    "assistant.started",
                    Map.of());
            ai.stream(
                    turn,
                    event -> {
                        if (!service.active(turn.conversationId(), turn.requestId()))
                            throw new CancellationException();
                        String type = Objects.toString(event.get("type"), "");
                        Map<String, Object> payload =
                                event.get("payload") instanceof Map<?, ?> m
                                        ? (Map<String, Object>) m
                                        : Map.of();
                        if (type.equals("assistant.delta")) {
                            String delta = Objects.toString(payload.get("delta"), "");
                            if (partial.length() + delta.length() > 200000)
                                throw new IllegalArgumentException("AI output limit");
                            partial.append(delta);
                            events.publish(
                                    turn.conversationId(),
                                    turn.requestId(),
                                    turn.messageId(),
                                    type,
                                    Map.of("delta", delta));
                        } else if (type.equals("assistant.completed")) {
                            var finalPayload = service.complete(turn, payload);
                            events.publish(
                                    turn.conversationId(),
                                    turn.requestId(),
                                    turn.messageId(),
                                    type,
                                    finalPayload);
                            completed[0] = true;
                        } else if (type.equals("assistant.failed"))
                            throw new IllegalStateException("AI generation failed");
                        else if (Set.of(
                                        "retrieval.completed",
                                        "citation.provisional",
                                        "tool.started",
                                        "tool.completed")
                                .contains(type))
                            events.publish(
                                    turn.conversationId(),
                                    turn.requestId(),
                                    turn.messageId(),
                                    type,
                                    payload);
                    });
            if (!completed[0] && service.active(turn.conversationId(), turn.requestId()))
                throw new IllegalStateException("AI stream ended without completion");
        } catch (CancellationException ignored) {
            ai.cancel(turn.conversationId(), turn.requestId());
            // Covers upstream cancellation not initiated by an explicit client cancel.
            service.failed(turn, "");
        } catch (Exception e) {
            log.warn(
                    "Chat stream failed conversation={} request={} cause={}",
                    turn.conversationId(),
                    turn.requestId(),
                    e.getClass().getSimpleName());
            if (service.failed(turn, partial.toString())) {
                try {
                    events.publish(
                            turn.conversationId(),
                            turn.requestId(),
                            turn.messageId(),
                            "assistant.failed",
                            Map.of(
                                    "code",
                                    "AI_PROVIDER_UNAVAILABLE",
                                    "message",
                                    "The answer could not be completed. Please retry.",
                                    "retryable",
                                    true));
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void onMessage(
            org.springframework.data.redis.connection.Message message, byte[] pattern) {
        String value = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            var frame = Json.object(value);
            UUID id = UUID.fromString(frame.get("conversationId").toString());
            if ("assistant.completed".equals(frame.get("type"))
                    && frame.get("payload") instanceof Map<?, ?> payload
                    && "CANCELLED".equals(payload.get("status"))
                    && frame.get("requestId") != null) {
                UUID request = UUID.fromString(frame.get("requestId").toString());
                var task = generations.remove(generationKey(id, request));
                if (task != null) task.cancel(true);
                ai.cancel(id, request);
            }
            for (var session : sessions.values())
                if (id.equals(conversation(session))) {
                    try {
                        sendAuthorized(session, value);
                    } catch (Exception unavailable) {
                        sessions.remove(session.getId());
                        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
                    }
                }
        } catch (Exception ignored) {
        }
    }

    private boolean sendAuthorized(WebSocketSession session, String value) throws Exception {
        var frame = Json.object(value);
        UUID request =
                frame.get("requestId") == null
                        ? null
                        : UUID.fromString(frame.get("requestId").toString());
        if (!service.canReceive(user(session), conversation(session), request)) {
            sessions.remove(session.getId());
            if (request != null && !service.active(conversation(session), request))
                ai.cancel(conversation(session), request);
            if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
            return false;
        }
        send(session, value);
        return true;
    }

    private void direct(
            WebSocketSession session,
            String type,
            UUID request,
            UUID message,
            Map<String, Object> payload)
            throws Exception {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("protocolVersion", "1.0");
        frame.put("eventId", UUID.randomUUID());
        frame.put("timestamp", Instant.now().toString());
        frame.put("traceId", request);
        frame.put("requestId", request);
        frame.put("conversationId", conversation(session));
        frame.put("messageId", message);
        frame.put("sequence", null);
        frame.put("payload", payload);
        send(session, Json.write(frame));
    }

    private void send(WebSocketSession session, String data) throws Exception {
        var target = sessions.getOrDefault(session.getId(), session);
        if (target.isOpen()) target.sendMessage(new TextMessage(data));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) throws Exception {
        sessions.remove(session.getId());
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        generations.values().forEach(task -> task.cancel(true));
        executor.shutdownNow();
    }

    private static UUID user(WebSocketSession session) {
        return (UUID) session.getAttributes().get("userId");
    }

    private static UUID conversation(WebSocketSession session) {
        return (UUID) session.getAttributes().get("conversationId");
    }
}
