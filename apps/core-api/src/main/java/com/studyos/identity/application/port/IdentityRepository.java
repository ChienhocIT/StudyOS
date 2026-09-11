package com.studyos.identity.application.port;
import java.util.*;import java.time.Instant;
public interface IdentityRepository {
    Optional<Map<String,Object>> byEmail(String email); Optional<Map<String,Object>> byId(UUID id);
    UUID create(String email,String passwordHash,String displayName);
    void refresh(UUID id,UUID user,String hash,UUID family,Instant expires);
    Optional<Map<String,Object>> lockRefresh(String hash);
    void revoke(UUID token);void revokeFamily(UUID family);void revokeHash(String hash);
}

