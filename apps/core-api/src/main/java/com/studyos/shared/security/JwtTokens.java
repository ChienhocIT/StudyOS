package com.studyos.shared.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import com.studyos.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokens {
    private final byte[] key, internalKey;
    private final String issuer;
    private final Clock clock;

    public JwtTokens(
            @Value("${studyos.jwt-secret}") String secret,
            @Value("${studyos.internal-jwt-secret}") String internalSecret,
            @Value("${studyos.jwt-issuer}") String issuer,
            Clock clock) {
        key = secret.getBytes(StandardCharsets.UTF_8);
        internalKey = internalSecret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.clock = clock;
        if (key.length < 32 || internalKey.length < 32)
            throw new IllegalArgumentException("JWT signing keys must contain at least 32 bytes");
    }

    public String access(UUID user) {
        return sign(key, user, "studyos-web", Duration.ofMinutes(15), Map.of());
    }

    public String websocket(UUID user, UUID conversation) {
        return sign(
                key,
                user,
                "studyos-ws",
                Duration.ofSeconds(60),
                Map.of("conversationId", conversation.toString()));
    }

    public String internal(UUID user, Map<String, Object> context) {
        return sign(
                internalKey, user, "studyos-ai", Duration.ofMinutes(2), Map.of("context", context));
    }

    private String sign(
            byte[] secret, UUID user, String audience, Duration ttl, Map<String, Object> extra) {
        try {
            Instant now = clock.instant();
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer(issuer)
                            .subject(user.toString())
                            .audience(audience)
                            .issueTime(Date.from(now))
                            .expirationTime(Date.from(now.plus(ttl)))
                            .jwtID(UUID.randomUUID().toString());
            extra.forEach(claims::claim);
            var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            token.sign(new MACSigner(secret));
            return token.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Token signing failed", e);
        }
    }

    public JWTClaimsSet verify(String token, String audience) {
        try {
            var jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new MACVerifier(key))) throw new Exception();
            var c = jwt.getJWTClaimsSet();
            if (!issuer.equals(c.getIssuer())
                    || !c.getAudience().contains(audience)
                    || c.getExpirationTime() == null
                    || !c.getExpirationTime().toInstant().isAfter(clock.instant())
                    || c.getIssueTime() == null
                    || c.getIssueTime().toInstant().isAfter(clock.instant().plusSeconds(10)))
                throw new Exception();
            UUID.fromString(c.getSubject());
            return c;
        } catch (Exception e) {
            throw new ApiException(
                    401, "INVALID_TOKEN", "The session token is invalid or expired.");
        }
    }
}
