package com.studyos.conversation.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyos.conversation.application.ConversationService;
import com.studyos.conversation.application.port.*;
import com.studyos.shared.persistence.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.Message;
import org.springframework.web.socket.*;

class StreamingDeliveryTest {
    @Test
    void redisPushRechecksAccessAndClosesRevokedSessionWithoutDeliveringContent() throws Exception {
        var service = mock(ConversationService.class);
        var ai = mock(ChatStream.class);
        var socket = new ConversationSocket(service, mock(StreamEvents.class), ai);
        var session = mock(WebSocketSession.class);
        UUID user = UUID.randomUUID(),
                conversation = UUID.randomUUID(),
                request = UUID.randomUUID();
        when(session.getId()).thenReturn("session");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes())
                .thenReturn(Map.of("userId", user, "conversationId", conversation));
        try {
            socket.afterConnectionEstablished(session);
            clearInvocations(session);
            String frame =
                    Json.write(
                            Map.of(
                                    "conversationId",
                                    conversation,
                                    "requestId",
                                    request,
                                    "type",
                                    "assistant.delta",
                                    "payload",
                                    Map.of("delta", "Private answer")));
            var message = mock(Message.class);
            when(message.getBody()).thenReturn(frame.getBytes(StandardCharsets.UTF_8));
            when(service.canReceive(user, conversation, request)).thenReturn(true);
            socket.onMessage(message, null);
            verify(session).sendMessage(any(TextMessage.class));
            clearInvocations(session);
            when(service.canReceive(user, conversation, request)).thenReturn(false);
            socket.onMessage(message, null);
            verify(session, never()).sendMessage(any());
            verify(session).close(CloseStatus.POLICY_VIOLATION);
            verify(ai).cancel(conversation, request);
            clearInvocations(session);
            socket.onMessage(message, null);
            verify(session, never()).sendMessage(any());
        } finally {
            socket.shutdown();
        }
    }
}
