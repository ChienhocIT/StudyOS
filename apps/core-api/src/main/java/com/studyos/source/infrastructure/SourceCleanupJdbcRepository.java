package com.studyos.source.infrastructure;

import com.studyos.source.application.port.SourceCleanupRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceCleanupJdbcRepository implements SourceCleanupRepository {
    private final JdbcTemplate jdbc;

    public SourceCleanupJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Source> begin(UUID workspaceId, UUID notebookId) {
        // Fence concurrent source inserts; the database guard takes matching shared locks.
        jdbc.queryForList("SELECT id FROM workspaces WHERE id=? FOR UPDATE", workspaceId);
        jdbc.queryForList(
                "SELECT id FROM notebooks WHERE workspace_id=? AND (?::uuid IS NULL OR id=?) ORDER BY id FOR UPDATE",
                workspaceId,
                notebookId,
                notebookId);
        return jdbc.query(
                """
                WITH candidates AS (
                  SELECT s.id FROM sources s
                  WHERE s.workspace_id=? AND (?::uuid IS NULL OR s.notebook_id=?)
                  AND s.status NOT IN ('DELETING','DELETED') ORDER BY s.id FOR UPDATE
                ), deleting AS (
                  UPDATE sources s SET status='DELETING',failure_code=NULL,failure_message=NULL,
                    retryable=false,updated_at=now()
                  FROM candidates c WHERE s.id=c.id
                  RETURNING s.id,s.workspace_id,s.notebook_id,s.current_version_id
                )
                SELECT d.*,v.object_key FROM deleting d
                JOIN source_versions v ON v.id=d.current_version_id AND v.source_id=d.id
                """,
                (rs, index) ->
                        new Source(
                                rs.getObject("id", UUID.class),
                                rs.getObject("workspace_id", UUID.class),
                                rs.getObject("notebook_id", UUID.class),
                                rs.getObject("current_version_id", UUID.class),
                                rs.getString("object_key")),
                workspaceId,
                notebookId,
                notebookId);
    }
}
