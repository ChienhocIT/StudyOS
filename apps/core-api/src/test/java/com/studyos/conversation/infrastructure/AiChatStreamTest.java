package com.studyos.conversation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyos.conversation.application.ConversationService.Turn;
import com.studyos.shared.security.JwtTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class AiChatStreamTest {
    @Test
    void cancellationClosesAnUpstreamThatStopsSendingEvents() throws Exception {
        var headersSent = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/chat/stream",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 0);
                    try (var body = exchange.getResponseBody()) {
                        body.write("\n".getBytes());
                        body.flush();
                        headersSent.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        server.start();
        var jwt = mock(JwtTokens.class);
        when(jwt.internal(any(), anyMap())).thenReturn("test-token");
        var stream = new AiChatStream(jwt, "http://127.0.0.1:" + server.getAddress().getPort());
        UUID conversation = UUID.randomUUID(), request = UUID.randomUUID();
        var turn =
                new Turn(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        conversation,
                        request,
                        UUID.randomUUID(),
                        "Question",
                        "ASK",
                        List.of(),
                        request.toString(),
                        false,
                        List.of());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result =
                    executor.submit(
                            () -> {
                                try {
                                    stream.stream(turn, event -> {});
                                    return false;
                                } catch (Exception expected) {
                                    return true;
                                }
                            });
            assertThat(headersSent.await(3, TimeUnit.SECONDS)).isTrue();
            stream.cancel(conversation, request);
            assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            stream.shutdown();
            server.stop(0);
        }
    }
}
