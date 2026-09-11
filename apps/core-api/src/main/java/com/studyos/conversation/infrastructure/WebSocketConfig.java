package com.studyos.conversation.infrastructure;

import com.studyos.conversation.api.ConversationSocket;
import com.studyos.conversation.application.ConversationService;
import com.studyos.identity.application.IdentityAccess;
import com.studyos.shared.security.JwtTokens;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ConversationSocket socket;
    private final ConversationService service;
    private final JwtTokens tokens;
    private final IdentityAccess identity;
    private final String[] origins;

    public WebSocketConfig(
            ConversationSocket socket,
            ConversationService service,
            JwtTokens tokens,
            IdentityAccess identity,
            @Value("${studyos.cors-origins}") String origins) {
        this.socket = socket;
        this.service = service;
        this.tokens = tokens;
        this.identity = identity;
        this.origins = origins.split(",");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/v1/conversations/*")
                .setAllowedOrigins(origins)
                .addInterceptors(
                        new HandshakeInterceptor() {
                            public boolean beforeHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Map<String, Object> attributes) {
                                try {
                                    String path = request.getURI().getPath();
                                    UUID conversation =
                                            UUID.fromString(
                                                    path.substring(path.lastIndexOf('/') + 1));
                                    String token =
                                            UriComponentsBuilder.fromUri(request.getURI())
                                                    .build()
                                                    .getQueryParams()
                                                    .getFirst("token");
                                    var claims = tokens.verify(token, "studyos-ws");
                                    UUID user = UUID.fromString(claims.getSubject());
                                    if (!conversation
                                                    .toString()
                                                    .equals(claims.getStringClaim("conversationId"))
                                            || !identity.isActive(user))
                                        throw new IllegalArgumentException();
                                    service.get(user, conversation);
                                    attributes.put("userId", user);
                                    attributes.put("conversationId", conversation);
                                    return true;
                                } catch (Exception e) {
                                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                                    return false;
                                }
                            }

                            public void afterHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Exception exception) {}
                        });
    }

    @Bean
    RedisMessageListenerContainer wsRedisListener(RedisConnectionFactory factory) {
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(factory);
        listener.addMessageListener(socket, new ChannelTopic(RedisStreamEvents.CHANNEL));
        return listener;
    }
}
