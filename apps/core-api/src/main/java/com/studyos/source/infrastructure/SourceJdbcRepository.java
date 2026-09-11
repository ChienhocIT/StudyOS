package com.studyos.source.infrastructure;

import com.studyos.shared.persistence.Rows;
import com.studyos.source.application.port.SourceRepository;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceJdbcRepository implements SourceRepository {
    private final JdbcClient db;

    public SourceJdbcRepository(JdbcClient db) {
        this.db = db;
    }

    private static final String SELECT =
            "select s.*,v.object_key,v.mime_type,v.size_bytes,v.checksum_sha256,v.version_no from sources s join source_versions v on v.id=s.current_version_id ";

    public Optional<Map<String, Object>> get(UUID id, boolean lock) {
        return db.sql(SELECT + "where s.id=:id " + (lock ? "for update of s" : ""))
                .param("id", id)
                .query(Rows::map)
                .optional();
    }

    public List<Map<String, Object>> list(UUID notebook) {
        return db.sql(
                        SELECT
                                + "where s.notebook_id=:n and s.status not in ('DELETING','DELETED') order by s.created_at desc")
                .param("n", notebook)
                .query(Rows::map)
                .list();
    }

    public Optional<Map<String, Object>> idempotent(UUID user, UUID notebook, String key) {
        if (key == null) return Optional.empty();
        return db.sql(
                        SELECT
                                + "where s.created_by=:u and s.notebook_id=:n and s.idempotency_key=:k")
                .param("u", user)
                .param("n", notebook)
                .param("k", key)
                .query(Rows::map)
                .optional();
    }

    public void lockIdempotency(UUID user, UUID notebook, String key) {
        if (key != null)
            db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
                    .param("key", user + ":" + notebook + ":" + key)
                    .query(
                            rs -> {
                                rs.next();
                                return true;
                            });
    }

    public UUID create(
            UUID user,
            UUID workspace,
            UUID notebook,
            String type,
            String title,
            String uri,
            String mime,
            long size,
            String checksum,
            String key,
            String status,
            UUID id,
            UUID version,
            String objectKey) {
        db.sql(
                        "insert into sources(id,workspace_id,notebook_id,created_by,type,title,canonical_uri,current_version_id,status,idempotency_key) values(:id,:w,:n,:u,cast(:t as source_type),:title,:uri,:v,cast(:s as source_status),:k)")
                .param("id", id)
                .param("w", workspace)
                .param("n", notebook)
                .param("u", user)
                .param("t", type)
                .param("title", title)
                .param("uri", uri)
                .param("v", version)
                .param("s", status)
                .param("k", key)
                .update();
        db.sql(
                        "insert into source_versions(id,source_id,version_no,object_key,mime_type,size_bytes,checksum_sha256) values(:v,:id,1,:o,:m,:size,:hash)")
                .param("v", version)
                .param("id", id)
                .param("o", objectKey)
                .param("m", mime)
                .param("size", size)
                .param("hash", checksum)
                .update();
        return id;
    }

    public void queued(UUID source, String checksum, long size, String objectKey) {
        db.sql(
                        "update source_versions set checksum_sha256=:hash,size_bytes=:size,object_key=:key where id=(select current_version_id from sources where id=:id)")
                .param("hash", checksum)
                .param("size", size)
                .param("key", objectKey)
                .param("id", source)
                .update();
        status(source, "QUEUED", null, null, false);
    }

    public void status(UUID id, String status, String failure, String detail, boolean retryable) {
        db.sql(
                        "update sources set status=cast(:status as source_status),failure_code=:failure,failure_message=:detail,retryable=:retry,updated_at=now() where id=:id")
                .param("id", id)
                .param("status", status)
                .param("failure", failure)
                .param("detail", detail)
                .param("retry", retryable)
                .update();
    }

    public List<UUID> ready(UUID notebook) {
        return db.sql("select id from sources where notebook_id=:n and status='READY' order by id")
                .param("n", notebook)
                .query(UUID.class)
                .list();
    }

    public List<Map<String, Object>> transcript(UUID version) {
        return db.sql(
                        "select id,segment_no,start_ms,end_ms,text,language,speaker from transcript_segments where source_version_id=:id order by segment_no")
                .param("id", version)
                .query(Rows::map)
                .list();
    }

    public boolean markEvent(UUID event) {
        return db.sql(
                                "insert into processed_events(consumer_name,event_id) values('core.source-state.v1',:id) on conflict do nothing")
                        .param("id", event)
                        .update()
                == 1;
    }

    public void parsed(UUID version, String normalized, String parser) {
        db.sql(
                        "update source_versions set normalized_object_key=:n,parser_version=:p,parse_status='SUCCEEDED' where id=:id")
                .param("n", normalized)
                .param("p", parser)
                .param("id", version)
                .update();
    }
}
