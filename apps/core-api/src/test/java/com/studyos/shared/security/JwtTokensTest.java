package com.studyos.shared.security;

import static org.assertj.core.api.Assertions.*;

import com.studyos.shared.web.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JwtTokensTest {
    private static final String KEY = "test-signing-secret-that-has-at-least-32-bytes";
    private final Instant now = Instant.parse("2026-09-01T00:00:00Z");

    private JwtTokens tokens(Instant time) {
        return new JwtTokens(
                KEY, KEY + "internal", "studyos-core", Clock.fixed(time, ZoneOffset.UTC));
    }

    @Test
    void tokensEnforceAudienceSignatureAndExpiry() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        String access = tokens(now).access(user);
        assertThat(tokens(now).verify(access, "studyos-web").getSubject())
                .isEqualTo(user.toString());
        assertThatThrownBy(() -> tokens(now).verify(access, "studyos-ws"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens(now.plusSeconds(901)).verify(access, "studyos-web"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens(now).verify(access + "altered", "studyos-web"))
                .isInstanceOf(ApiException.class);
        String ws = tokens(now).websocket(user, conversation);
        assertThat(tokens(now).verify(ws, "studyos-ws").getClaim("conversationId"))
                .isEqualTo(conversation.toString());
        assertThatThrownBy(() -> tokens(now.plusSeconds(61)).verify(ws, "studyos-ws"))
                .isInstanceOf(ApiException.class);
    }
}
