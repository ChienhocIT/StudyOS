package com.studyos.conversation.infrastructure;

import com.studyos.conversation.application.ConversationService.Turn;
import com.studyos.conversation.application.port.ChatStream;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.security.JwtTokens;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiChatStream implements ChatStream {
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
    private final JwtTokens jwt;
    private final String aiUrl;
    private final ConcurrentMap<String, ActiveStream> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var thread = new Thread(r, "ai-stream-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static String key(UUID conversation, UUID request) {
        return conversation + ":" + request;
    }

    private static final class ActiveStream {
        final Thread owner = Thread.currentThread();
        InputStream input;
        boolean cancelled;

        synchronized void attach(InputStream value) throws IOException {
            if (cancelled) {
                value.close();
                throw new InterruptedIOException("AI stream cancelled");
            }
            input = value;
        }

        synchronized void cancel() {
            cancelled = true;
            owner.interrupt();
            if (input != null)
                try {
                    input.close();
                } catch (IOException ignored) {
                }
        }
    }

    @Override
    public void cancel(UUID conversation, UUID request) {
        var stream = active.get(key(conversation, request));
        if (stream != null) stream.cancel();
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        deadlines.shutdownNow();
        active.values().forEach(ActiveStream::cancel);
    }

    public AiChatStream(JwtTokens jwt, @Value("${studyos.ai-url}") String aiUrl) {
        this.jwt = jwt;
        this.aiUrl = aiUrl;
    }

    public void stream(Turn t, Consumer<Map<String, Object>> events) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String streamKey = key(t.conversationId(), t.requestId());
        var running = new ActiveStream();
        if (active.putIfAbsent(streamKey, running) != null)
            throw new IllegalStateException("Generation already streaming");
        var deadline = deadlines.schedule(running::cancel, 180, TimeUnit.SECONDS);
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("userId", t.userId().toString());
            context.put("workspaceId", t.workspaceId().toString());
            context.put("notebookId", t.notebookId().toString());
            context.put("sourceIds", t.sourceIds().stream().map(UUID::toString).toList());
            context.put("conversationId", t.conversationId().toString());
            context.put("traceId", t.traceId());
            context.put("permissions", List.of("chat:generate"));
            Map<String, Object> body =
                    Map.of(
                            "context",
                            context,
                            "requestId",
                            t.requestId(),
                            "messageId",
                            t.messageId(),
                            "content",
                            t.content(),
                            "mode",
                            t.mode(),
                            "history",
                            t.history());
            var request =
                    HttpRequest.newBuilder(URI.create(aiUrl + "/internal/v1/chat/stream"))
                            .timeout(Duration.ofSeconds(180))
                            .header("Authorization", "Bearer " + jwt.internal(t.userId(), context))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/x-ndjson")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            running.attach(response.body());
            try (var input =
                    new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                if (response.statusCode() != 200)
                    throw new IOException("AI returned HTTP " + response.statusCode());
                String line;
                while ((line = input.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (line.length() > 1000000) throw new IOException("AI event too large");
                    if (!line.isBlank()) events.accept(Json.object(line));
                }
            }
        } finally {
            deadline.cancel(false);
            active.remove(streamKey, running);
        }
    }
}
