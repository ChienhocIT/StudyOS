package com.studyos.studio.infrastructure;

import com.studyos.studio.application.SourceArtifactInvalidation;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresSourceArtifactInvalidation implements SourceArtifactInvalidation {
    private final JdbcTemplate jdbc;

    public PostgresSourceArtifactInvalidation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void invalidate(UUID workspace, UUID notebook, UUID source) {
        // scope_json contains the explicit READY source set resolved at request creation.
        jdbc.update(
                """
                UPDATE artifact_jobs j SET status='FAILED',error_code='SOURCE_DELETED',stale_at=now(),
                  result_json=NULL,updated_at=now()
                FROM notebooks n WHERE j.notebook_id=n.id AND n.id=? AND n.workspace_id=?
                AND j.scope_json->'sourceIds' @> jsonb_build_array(?::text)
                """,
                notebook,
                workspace,
                source.toString());
        jdbc.update(
                """
                UPDATE ai_usage_reservations r SET status='FAILED',finished_at=now()
                FROM artifact_jobs j WHERE r.feature='ARTIFACT' AND r.request_key=j.id::text
                AND r.user_id=j.user_id AND j.notebook_id=? AND j.stale_at IS NOT NULL
                AND r.status='RESERVED'
                """,
                notebook);
        jdbc.update(
                """
                UPDATE quizzes q SET status='STALE' FROM artifact_jobs j
                WHERE q.artifact_job_id=j.id AND j.notebook_id=? AND j.stale_at IS NOT NULL
                """,
                notebook);
        jdbc.update(
                """
                UPDATE flashcard_decks d SET status='STALE' FROM artifact_jobs j
                WHERE d.artifact_job_id=j.id AND j.notebook_id=? AND j.stale_at IS NOT NULL
                """,
                notebook);
    }
}
