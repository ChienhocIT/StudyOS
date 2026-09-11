package com.studyos.identity.application;

import com.studyos.identity.application.port.IdentityRepository;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.security.JwtTokens;
import com.studyos.shared.web.ApiException;
import com.studyos.workspace.application.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements IdentityAccess {
    private final IdentityRepository repo;
    private final PasswordEncoder passwords;
    private final JwtTokens jwt;
    private final WorkspaceService workspaces;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(
            IdentityRepository repo,
            PasswordEncoder passwords,
            JwtTokens jwt,
            WorkspaceService workspaces,
            Clock clock) {
        this.repo = repo;
        this.passwords = passwords;
        this.jwt = jwt;
        this.workspaces = workspaces;
        this.clock = clock;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Map<String, Object> register(String email, String password, String name) {
        if (repo.byEmail(email).isPresent())
            throw ApiException.conflict(
                    "EMAIL_ALREADY_REGISTERED", "This email is already registered.");
        UUID id =
                repo.create(
                        email.trim().toLowerCase(Locale.ROOT),
                        passwords.encode(password),
                        name.trim());
        workspaces.create(id, name.trim() + "'s workspace");
        return response(id, UUID.randomUUID());
    }

    @Transactional
    public Map<String, Object> login(String email, String password) {
        var row = repo.byEmail(email.trim());
        String hash = row.map(v -> (String) v.get("passwordHash")).orElse(dummyHash);
        boolean valid = passwords.matches(password, hash == null ? dummyHash : hash);
        if (!valid || row.isEmpty() || !"ACTIVE".equals(row.get().get("status")))
            throw unauthorized();
        return response(Rows.uuid(row.get(), "id"), UUID.randomUUID());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> refresh(String token) {
        var row = repo.lockRefresh(hash(token)).orElseThrow(AuthService::unauthorized);
        UUID family = Rows.uuid(row, "familyId");
        if (row.get("revokedAt") != null) {
            repo.revokeFamily(family);
            throw unauthorized();
        }
        if (!((Instant) row.get("expiresAt")).isAfter(clock.instant())
                || !isActive(Rows.uuid(row, "userId"))) {
            repo.revoke(Rows.uuid(row, "id"));
            throw unauthorized();
        }
        repo.revoke(Rows.uuid(row, "id"));
        return tokens(Rows.uuid(row, "userId"), family);
    }

    @Transactional
    public void logout(String token) {
        repo.revokeHash(hash(token));
    }

    public boolean isActive(UUID id) {
        return repo.byId(id).map(row -> "ACTIVE".equals(row.get("status"))).orElse(false);
    }

    public Map<String, Object> me(UUID id) {
        var row =
                repo.byId(id)
                        .filter(v -> "ACTIVE".equals(v.get("status")))
                        .orElseThrow(AuthService::unauthorized);
        return Map.of(
                "id",
                id,
                "email",
                row.get("email").toString(),
                "displayName",
                row.get("displayName"),
                "locale",
                row.get("locale"),
                "timezone",
                row.get("timezone"));
    }

    private Map<String, Object> response(UUID id, UUID family) {
        return Map.of("user", me(id), "tokens", tokens(id, family));
    }

    private Map<String, Object> tokens(UUID id, UUID family) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repo.refresh(
                UUID.randomUUID(),
                id,
                hash(refresh),
                family,
                clock.instant().plus(Duration.ofDays(30)));
        return Map.of(
                "accessToken",
                jwt.access(id),
                "refreshToken",
                refresh,
                "expiresIn",
                900,
                "tokenType",
                "Bearer");
    }

    public static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(
                401, "INVALID_CREDENTIALS", "Email, password or session is invalid.");
    }
}
