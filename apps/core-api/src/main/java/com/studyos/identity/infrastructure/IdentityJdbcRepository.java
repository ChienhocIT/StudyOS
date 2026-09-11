package com.studyos.identity.infrastructure;

import com.studyos.identity.application.port.IdentityRepository;
import com.studyos.shared.persistence.Rows;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityJdbcRepository implements IdentityRepository {
    private final JdbcClient db;

    public IdentityJdbcRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Map<String, Object>> byEmail(String email) {
        return db.sql("select * from users where email=:email")
                .param("email", email)
                .query(Rows::map)
                .optional();
    }

    public Optional<Map<String, Object>> byId(UUID id) {
        return db.sql("select * from users where id=:id")
                .param("id", id)
                .query(Rows::map)
                .optional();
    }

    public UUID create(String email, String hash, String name) {
        UUID id = UUID.randomUUID();
        db.sql(
                        "insert into users(id,email,password_hash,display_name) values(:id,:email,:hash,:name)")
                .param("id", id)
                .param("email", email)
                .param("hash", hash)
                .param("name", name)
                .update();
        return id;
    }

    public void refresh(UUID id, UUID user, String hash, UUID family, Instant expires) {
        db.sql(
                        "insert into refresh_tokens(id,user_id,token_hash,family_id,expires_at) values(:id,:user,:hash,:family,:expires)")
                .param("id", id)
                .param("user", user)
                .param("hash", hash)
                .param("family", family)
                .param("expires", java.sql.Timestamp.from(expires))
                .update();
    }

    public Optional<Map<String, Object>> lockRefresh(String hash) {
        return db.sql("select * from refresh_tokens where token_hash=:hash for update")
                .param("hash", hash)
                .query(Rows::map)
                .optional();
    }

    public void revoke(UUID id) {
        db.sql("update refresh_tokens set revoked_at=coalesce(revoked_at,now()) where id=:id")
                .param("id", id)
                .update();
    }

    public void revokeFamily(UUID family) {
        db.sql(
                        "update refresh_tokens set revoked_at=coalesce(revoked_at,now()) where family_id=:family")
                .param("family", family)
                .update();
    }

    public void revokeHash(String hash) {
        db.sql(
                        "update refresh_tokens set revoked_at=coalesce(revoked_at,now()) where token_hash=:hash")
                .param("hash", hash)
                .update();
    }
}
