package com.studyos.conversation.application;

import com.studyos.conversation.application.port.ConversationRepository;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.security.JwtTokens;
import com.studyos.shared.usage.AiUsagePolicy;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.SourceLookup;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private final ConversationRepository repo;
    private final NotebookAccess notebooks;
    private final SourceLookup sources;
    private final JwtTokens jwt;
    private final String apiUrl;
    private final AiUsagePolicy usage;

    public ConversationService(
            ConversationRepository repo,
            NotebookAccess notebooks,
            SourceLookup sources,
            JwtTokens jwt,
            @Value("${studyos.public-api-url}") String apiUrl,
            AiUsagePolicy usage) {
        this.repo = repo;
        this.notebooks = notebooks;
        this.sources = sources;
        this.jwt = jwt;
        this.apiUrl = apiUrl;
        this.usage = usage;
    }

    public record Turn(
            UUID userId,
            UUID workspaceId,
            UUID notebookId,
            UUID conversationId,
            UUID requestId,
            UUID messageId,
            String content,
            String mode,
            List<UUID> sourceIds,
            String traceId,
            boolean duplicate,
            List<Map<String, Object>> history) {}

    public Map<String, Object> get(UUID user, UUID id) {
        return authorized(user, id, false);
    }

    private Map<String, Object> authorized(UUID user, UUID id, boolean lock) {
        var row =
                repo.get(id, lock)
                        .filter(r -> user.equals(Rows.uuid(r, "userId")))
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "CONVERSATION_NOT_FOUND",
                                                "Conversation not found."));
        notebooks.requireRead(user, Rows.uuid(row, "notebookId"));
        return row;
    }

    @Transactional
    public Map<String, Object> create(UUID user, UUID notebook, String mode, String title) {
        var scope = notebooks.requireWrite(user, notebook);
        return get(
                user,
                repo.create(
                        user, scope.workspaceId(), notebook, mode == null ? "ASK" : mode, title));
    }

    public List<Map<String, Object>> list(UUID user, UUID notebook) {
        notebooks.requireRead(user, notebook);
        return repo.list(user, notebook);
    }

    public record MessagePage(
            List<Map<String, Object>> items, boolean hasMore, String nextCursor) {}

    public List<Map<String, Object>> messages(UUID user, UUID id, String cursor) {
        return messagePage(user, id, cursor, null, 100).items();
    }

    public MessagePage messagePage(UUID user, UUID id, String cursor, Long before, int size) {
        get(user, id);
        Long after = cursor == null ? null : Long.parseLong(cursor);
        if ((after != null && after < 0)
                || (before != null && before < 1)
                || size < 1
                || size > 200
                || (after != null && before != null))
            throw ApiException.badRequest(
                    "INVALID_CURSOR", "Use one valid cursor and a page size between 1 and 200.");
        var rows = new ArrayList<>(repo.messagePage(id, after, before, size + 1));
        boolean more = rows.size() > size;
        if (more) rows.removeLast();
        String next = rows.isEmpty() ? null : rows.getLast().get("sequenceNo").toString();
        if (after == null) Collections.reverse(rows);
        var items =
                rows.stream()
                        .map(
                                row -> {
                                    var result = new LinkedHashMap<>(row);
                                    result.put("citations", repo.citations(Rows.uuid(row, "id")));
                                    return (Map<String, Object>) result;
                                })
                        .toList();
        return new MessagePage(items, more, next);
    }

    public Map<String, Object> wsToken(UUID user, UUID id) {
        get(user, id);
        String token = jwt.websocket(user, id);
        return Map.of(
                "token",
                token,
                "expiresAt",
                Instant.now().plusSeconds(60),
                "websocketUrl",
                apiUrl.replaceFirst("^http", "ws")
                        + "/ws/v1/conversations/"
                        + id
                        + "?token="
                        + token);
    }

    @Transactional
    public Turn begin(
            UUID user,
            UUID conversation,
            UUID request,
            String content,
            String mode,
            List<UUID> selected) {
        if (content == null || content.isBlank() || content.length() > 12000)
            throw ApiException.badRequest(
                    "INVALID_MESSAGE", "Message must contain between 1 and 12000 characters.");
        var row = authorized(user, conversation, true);
        UUID notebook = Rows.uuid(row, "notebookId");
        notebooks.requireWrite(user, notebook);
        var existing = repo.generation(conversation, request, false);
        if (existing.isPresent()) {
            var g = existing.get();
            return new Turn(
                    user,
                    Rows.uuid(row, "workspaceId"),
                    notebook,
                    conversation,
                    request,
                    Rows.uuid(g, "assistantMessageId"),
                    content,
                    mode,
                    List.of(),
                    request.toString(),
                    true,
                    List.of());
        }
        repo.lockUser(user);
        if (repo.activeCount(user) >= 3)
            throw new ApiException(429, "GENERATION_LIMIT", "Wait for an active answer to finish.");
        var sourceIds = sources.requireReady(user, notebook, selected);
        String chosenMode = mode == null ? row.get("mode").toString() : mode;
        if (!Set.of("ASK", "SOCRATIC", "FEYNMAN", "EXAM", "INTERVIEW", "ELI5", "EXPERT")
                .contains(chosenMode))
            throw ApiException.badRequest("INVALID_MODE", "Unsupported conversation mode.");
        usage.reserve(user, Rows.uuid(row, "workspaceId"), "CHAT", usageKey(conversation, request));
        var history =
                repo.recentHistory(conversation, 20).stream()
                        .map(
                                m ->
                                        Map.<String, Object>of(
                                                "role",
                                                m.get("role").toString().toLowerCase(Locale.ROOT),
                                                "content",
                                                m.get("content")))
                        .toList();
        UUID message;
        try {
            message =
                    repo.begin(conversation, request, user, content, sourceIds, request.toString());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict(
                    "GENERATION_ACTIVE", "This conversation already has an active answer.");
        }
        return new Turn(
                user,
                Rows.uuid(row, "workspaceId"),
                notebook,
                conversation,
                request,
                message,
                content,
                chosenMode,
                sourceIds,
                request.toString(),
                false,
                history);
    }

    @Transactional
    public boolean active(UUID conversation, UUID request) {
        var generation = repo.generation(conversation, request, true);
        if (generation.isEmpty() || !"STREAMING".equals(generation.get().get("status")))
            return false;
        return streamScopeValid(
                Rows.uuid(generation.get(), "userId"), conversation, request, generation.get());
    }

    @Transactional
    public boolean canReceive(UUID user, UUID conversation, UUID request) {
        var generation =
                request == null
                        ? Optional.<Map<String, Object>>empty()
                        : repo.generation(conversation, request, true);
        // A stale or mismatched session must never terminate somebody else's generation.
        if (generation.isPresent() && !user.equals(Rows.uuid(generation.get(), "userId")))
            return false;
        return streamScopeValid(user, conversation, request, generation.orElse(null));
    }

    private boolean streamScopeValid(
            UUID user, UUID conversation, UUID request, Map<String, Object> generation) {
        try {
            var row = authorized(user, conversation, false);
            UUID notebook = Rows.uuid(row, "notebookId");
            notebooks.requireWrite(user, notebook);
            if (generation != null
                    && generation.get("sourceIdsJson") instanceof List<?> selected
                    && !selected.isEmpty())
                sources.requireReady(
                        user,
                        notebook,
                        selected.stream().map(id -> UUID.fromString(id.toString())).toList());
            return true;
        } catch (ApiException revoked) {
            // Catch access denial inside the transaction so terminal state and budget release
            // commit.
            if (generation != null && "STREAMING".equals(generation.get("status"))) {
                repo.finish(conversation, request, "", "CANCELLED", null);
                usage.failed(user, "CHAT", usageKey(conversation, request));
            }
            return false;
        }
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> complete(Turn turn, Map<String, Object> payload) {
        var generation =
                repo.generation(turn.conversationId(), turn.requestId(), true).orElseThrow();
        if (!"STREAMING".equals(generation.get("status")))
            return Map.of(
                    "status",
                    generation.get("status"),
                    "citationIds",
                    List.of(),
                    "groundingStatus",
                    "INSUFFICIENT");
        if (!streamScopeValid(turn.userId(), turn.conversationId(), turn.requestId(), generation))
            return Map.of(
                    "status",
                    "CANCELLED",
                    "citationIds",
                    List.of(),
                    "groundingStatus",
                    "INSUFFICIENT");
        String content = Objects.toString(payload.get("content"), "");
        if (content.length() > 200000)
            throw ApiException.badRequest(
                    "AI_OUTPUT_TOO_LARGE", "The answer exceeded its size limit.");
        String grounding = Objects.toString(payload.get("groundingStatus"), "INSUFFICIENT");
        if (!Set.of("SUPPORTED", "PARTIAL", "INSUFFICIENT").contains(grounding))
            grounding = "INSUFFICIENT";
        List<Map<String, Object>> citations =
                payload.get("citations") instanceof List<?> l
                        ? (List<Map<String, Object>>) l
                        : List.of();
        if (citations.size() > 100) throw new IllegalArgumentException("Too many citations");
        List<UUID> ids = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int rank = 0;
        for (var candidate : citations) {
            UUID chunk = UUID.fromString(candidate.get("chunkId").toString());
            String key = candidate.get("key").toString();
            if (!key.matches("C[1-9][0-9]{0,3}") || !keys.add(key))
                throw new IllegalArgumentException("Invalid citation key");
            var match =
                    repo.citationChunk(
                                    chunk, turn.workspaceId(), turn.notebookId(), turn.sourceIds())
                            .orElseThrow(
                                    () -> new IllegalArgumentException("Invalid citation scope"));
            if (candidate.get("sourceId") != null
                    && !match.get("sourceId")
                            .toString()
                            .equals(candidate.get("sourceId").toString()))
                throw new IllegalArgumentException("Invalid citation source");
            ids.add(repo.citation(turn.messageId(), chunk, key, ++rank));
        }
        if (ids.isEmpty()) grounding = "INSUFFICIENT";
        repo.finish(turn.conversationId(), turn.requestId(), content, "COMPLETED", grounding);
        usage.complete(
                turn.userId(),
                "CHAT",
                usageKey(turn.conversationId(), turn.requestId()),
                payload.get("usage") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                turn.traceId());
        return Map.of(
                "status",
                "COMPLETED",
                "groundingStatus",
                grounding,
                "citationIds",
                ids,
                "usage",
                payload.getOrDefault("usage", Map.of()));
    }

    @Transactional
    public boolean cancel(UUID user, UUID conversation, UUID request) {
        authorized(user, conversation, false);
        var row = repo.generation(conversation, request, true);
        if (row.isEmpty() || !"STREAMING".equals(row.get().get("status"))) return false;
        repo.finish(conversation, request, "", "CANCELLED", null);
        usage.failed(user, "CHAT", usageKey(conversation, request));
        return true;
    }

    @Transactional
    public boolean failed(Turn turn, String partial) {
        var row = repo.generation(turn.conversationId(), turn.requestId(), true);
        if (row.isPresent() && "STREAMING".equals(row.get().get("status"))) {
            repo.finish(turn.conversationId(), turn.requestId(), partial, "FAILED", null);
            usage.failed(turn.userId(), "CHAT", usageKey(turn.conversationId(), turn.requestId()));
            return true;
        }
        return false;
    }

    private static String usageKey(UUID conversation, UUID request) {
        return conversation + ":" + request;
    }

    @Transactional
    public void feedback(UUID user, UUID message, int rating, String reason, String comment) {
        var row =
                repo.message(message)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "MESSAGE_NOT_FOUND", "Message not found."));
        authorized(user, Rows.uuid(row, "conversationId"), false);
        if (!"ASSISTANT".equals(row.get("role")))
            throw ApiException.badRequest(
                    "INVALID_FEEDBACK", "Feedback is only supported for assistant messages.");
        repo.feedback(user, message, rating, reason, comment);
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void expireStale() {
        repo.expireStale();
    }
}
