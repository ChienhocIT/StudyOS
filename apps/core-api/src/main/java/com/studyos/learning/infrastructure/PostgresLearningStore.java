package com.studyos.learning.infrastructure;

import com.studyos.learning.application.port.LearningStore;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresLearningStore implements LearningStore {
    private final JdbcTemplate jdbc;

    public PostgresLearningStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean conceptInNotebook(UUID conceptId, UUID notebookId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM concepts WHERE id=? AND notebook_id=?)",
                        Boolean.class,
                        conceptId,
                        notebookId));
    }

    public Map<String, Object> appendEvidence(
            UUID userId,
            UUID conceptId,
            String type,
            BigDecimal score,
            BigDecimal weight,
            String key,
            UUID activityId) {
        jdbc.update(
                "INSERT INTO user_concept_mastery(user_id,concept_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                userId,
                conceptId);
        jdbc.queryForObject(
                "SELECT version FROM user_concept_mastery WHERE user_id=? AND concept_id=? FOR UPDATE",
                Long.class,
                userId,
                conceptId);
        int inserted =
                jdbc.update(
                        """
                INSERT INTO mastery_evidence(user_id,concept_id,evidence_type,score,weight,evidence_key,source_ref)
                VALUES (?,?,?::evidence_type,?,?,?,?::jsonb) ON CONFLICT DO NOTHING
                """,
                        userId,
                        conceptId,
                        type,
                        score,
                        weight,
                        key,
                        Json.write(Map.of("activityId", activityId, "policy", "mastery-v0")));
        if (inserted == 0) return Map.of();
        return jdbc.queryForObject(
                """
                SELECT coalesce(sum(score*weight),0) AS weighted_scores, coalesce(sum(weight),0) AS total_weight
                FROM mastery_evidence WHERE user_id=? AND concept_id=?
                """,
                Rows::map,
                userId,
                conceptId);
    }

    public void updateProjection(
            UUID userId, UUID conceptId, BigDecimal score, BigDecimal confidence) {
        jdbc.update(
                """
                UPDATE user_concept_mastery SET mastery_score=?, confidence=?,
                evidence_count=(SELECT count(*) FROM mastery_evidence WHERE user_id=? AND concept_id=?),
                last_evidence_at=now(), updated_at=now(), version=version+1 WHERE user_id=? AND concept_id=?
                """,
                score,
                confidence,
                userId,
                conceptId,
                userId,
                conceptId);
    }

    public List<Map<String, Object>> concepts(UUID notebookId) {
        return jdbc.query(
                """
                SELECT c.id,c.name,c.description,c.difficulty,
                  coalesce((SELECT jsonb_agg(r.to_concept_id) FROM concept_relations r
                    WHERE r.from_concept_id=c.id AND r.notebook_id=c.notebook_id AND r.relation_type='PREREQUISITE'),'[]'::jsonb) AS prerequisite_ids
                FROM concepts c WHERE c.notebook_id=? ORDER BY c.normalized_name,c.id LIMIT 1000
                """,
                Rows::map,
                notebookId);
    }

    public List<Map<String, Object>> mastery(UUID userId, UUID notebookId) {
        return jdbc.query(
                """
                SELECT c.id AS concept_id,c.name AS concept_name,coalesce(m.mastery_score,0) AS mastery_score,
                coalesce(m.confidence,0) AS confidence,coalesce(m.evidence_count,0) AS evidence_count,m.last_evidence_at
                FROM concepts c LEFT JOIN user_concept_mastery m ON m.concept_id=c.id AND m.user_id=?
                WHERE c.notebook_id=? ORDER BY coalesce(m.mastery_score,0),c.name LIMIT 1000
                """,
                Rows::map,
                userId,
                notebookId);
    }

    public void acceptConcept(
            UUID workspaceId,
            UUID notebookId,
            UUID sourceId,
            String name,
            String normalizedName,
            String description,
            BigDecimal confidence,
            List<UUID> chunks) {
        if (chunks.isEmpty()) return;
        for (UUID chunk : chunks) {
            boolean valid =
                    Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    """
                    SELECT EXISTS(SELECT 1 FROM document_chunks c JOIN sources s ON s.current_version_id=c.source_version_id
                      WHERE c.id=? AND c.workspace_id=? AND c.notebook_id=? AND s.id=? AND s.status NOT IN ('DELETED','DELETING'))
                    """,
                                    Boolean.class,
                                    chunk,
                                    workspaceId,
                                    notebookId,
                                    sourceId));
            if (!valid)
                throw ApiException.badRequest(
                        "CONCEPT_PROVENANCE_INVALID",
                        "Concept evidence is outside the current source.");
        }
        UUID id =
                jdbc.queryForObject(
                        """
                INSERT INTO concepts(workspace_id,notebook_id,name,normalized_name,description,extraction_confidence)
                VALUES (?,?,?,?,?,?) ON CONFLICT(notebook_id,normalized_name) DO UPDATE
                  SET extraction_confidence=greatest(concepts.extraction_confidence,excluded.extraction_confidence),updated_at=now()
                RETURNING id
                """,
                        UUID.class,
                        workspaceId,
                        notebookId,
                        name,
                        normalizedName,
                        description,
                        confidence);
        for (UUID chunk : chunks)
            jdbc.update(
                    """
                INSERT INTO chunk_concepts(chunk_id,concept_id,confidence) VALUES (?,?,?)
                ON CONFLICT(chunk_id,concept_id) DO UPDATE SET confidence=greatest(chunk_concepts.confidence,excluded.confidence)
                """,
                    chunk,
                    id,
                    confidence);
    }

    public List<Map<String, Object>> goals(UUID userId) {
        return jdbc.query(
                """
                SELECT g.* FROM learning_goals g JOIN workspace_members m ON m.workspace_id=g.workspace_id AND m.user_id=g.user_id
                JOIN workspaces w ON w.id=g.workspace_id AND w.status='ACTIVE'
                WHERE g.user_id=? ORDER BY g.created_at DESC,g.id LIMIT 200
                """,
                Rows::map,
                userId);
    }

    public Optional<Map<String, Object>> goal(UUID userId, UUID goalId) {
        return jdbc
                .query(
                        "SELECT * FROM learning_goals WHERE id=? AND user_id=?",
                        Rows::map,
                        goalId,
                        userId)
                .stream()
                .findFirst();
    }

    public Map<String, Object> createGoal(
            UUID userId,
            UUID workspaceId,
            String title,
            String description,
            String targetDate,
            Integer weeklyMinutes) {
        return jdbc.queryForObject(
                """
                INSERT INTO learning_goals(user_id,workspace_id,title,description,target_date,weekly_minutes)
                VALUES (?,?,?,?,?::date,?) RETURNING *
                """,
                Rows::map,
                userId,
                workspaceId,
                title,
                description,
                targetDate,
                weeklyMinutes);
    }

    public Map<String, Object> updateGoal(UUID userId, UUID goalId, Map<String, Object> changes) {
        Map<String, String> allowed =
                Map.of(
                        "title",
                        "title",
                        "description",
                        "description",
                        "targetDate",
                        "target_date",
                        "weeklyMinutes",
                        "weekly_minutes",
                        "status",
                        "status");
        for (var field : changes.entrySet()) {
            if (!allowed.containsKey(field.getKey()))
                throw ApiException.badRequest("VALIDATION_FAILED", "Unknown goal field.");
            String cast = field.getKey().equals("targetDate") ? "::date" : "";
            jdbc.update(
                    "UPDATE learning_goals SET "
                            + allowed.get(field.getKey())
                            + "=?"
                            + cast
                            + ",updated_at=now() WHERE id=? AND user_id=?",
                    field.getValue(),
                    goalId,
                    userId);
        }
        return goal(userId, goalId).orElseThrow();
    }

    public List<Map<String, Object>> recommendations(UUID userId) {
        // Serialize snapshot replacement for one learner; no recommendations survive a membership
        // revocation.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, userId);
        jdbc.update(
                "UPDATE learning_recommendations SET status='EXPIRED' WHERE user_id=? AND status='PENDING'",
                userId);
        List<Map<String, Object>> candidates =
                jdbc.query(
                        """
                SELECT n.id AS notebook_id,c.id AS concept_id,c.name,coalesce(m.mastery_score,0) AS mastery_score,
                coalesce(m.confidence,0) AS confidence,
                (SELECT count(*) FROM flashcards f JOIN flashcard_decks d ON d.id=f.deck_id
                  WHERE d.user_id=? AND d.notebook_id=n.id AND d.status='READY' AND coalesce(f.due_at,now())<=now()) AS due_cards,
                EXISTS(SELECT 1 FROM learning_goals g WHERE g.user_id=? AND g.workspace_id=n.workspace_id AND g.status='ACTIVE') AS active_goal
                FROM notebooks n JOIN workspace_members wm ON wm.workspace_id=n.workspace_id AND wm.user_id=?
                JOIN workspaces w ON w.id=n.workspace_id AND w.status='ACTIVE'
                LEFT JOIN concepts c ON c.notebook_id=n.id AND EXISTS (
                  SELECT 1 FROM chunk_concepts cc JOIN document_chunks dc ON dc.id=cc.chunk_id
                  JOIN sources s ON s.current_version_id=dc.source_version_id
                  WHERE cc.concept_id=c.id AND s.status='READY'
                    AND s.workspace_id=n.workspace_id AND s.notebook_id=n.id
                    AND dc.workspace_id=n.workspace_id AND dc.notebook_id=n.id)
                LEFT JOIN user_concept_mastery m ON m.concept_id=c.id AND m.user_id=?
                WHERE n.status='ACTIVE' ORDER BY coalesce(m.mastery_score,0),n.id,c.id LIMIT 100
                """,
                        Rows::map,
                        userId,
                        userId,
                        userId,
                        userId);
        Set<UUID> reviewNotebooks = new HashSet<>();
        for (var candidate : candidates) {
            UUID notebookId = Rows.uuid(candidate, "notebookId");
            long due = ((Number) candidate.get("dueCards")).longValue();
            if (due > 0 && reviewNotebooks.add(notebookId)) {
                insertRecommendation(
                        userId,
                        notebookId,
                        null,
                        "REVIEW_FLASHCARDS",
                        new BigDecimal("1.0"),
                        Map.of(
                                "policy",
                                "recommendation-v0",
                                "dueCards",
                                due,
                                "message",
                                "Ôn thẻ đến hạn trước khi học thêm."));
            }
            if (candidate.get("conceptId") == null) continue;
            BigDecimal mastery = new BigDecimal(candidate.get("masteryScore").toString());
            boolean activeGoal = Boolean.TRUE.equals(candidate.get("activeGoal"));
            BigDecimal priority =
                    BigDecimal.ONE
                            .subtract(mastery)
                            .multiply(new BigDecimal("0.7"))
                            .add(activeGoal ? new BigDecimal("0.15") : BigDecimal.ZERO);
            insertRecommendation(
                    userId,
                    notebookId,
                    Rows.uuid(candidate, "conceptId"),
                    mastery.signum() == 0 ? "LEARN_NEW" : "RETRY_QUIZ",
                    priority,
                    Map.of(
                            "policy",
                            "recommendation-v0",
                            "masteryGap",
                            BigDecimal.ONE.subtract(mastery),
                            "goalAligned",
                            activeGoal,
                            "conceptName",
                            candidate.get("name")));
        }
        return jdbc.query(
                """
                SELECT id,notebook_id,concept_id,action_type,priority_score,reason_json AS reason,10 AS estimated_minutes
                FROM learning_recommendations WHERE user_id=? AND status='PENDING' ORDER BY priority_score DESC,id LIMIT 20
                """,
                Rows::map,
                userId);
    }

    private void insertRecommendation(
            UUID userId,
            UUID notebookId,
            UUID conceptId,
            String action,
            BigDecimal priority,
            Object reason) {
        jdbc.update(
                """
                INSERT INTO learning_recommendations(user_id,notebook_id,concept_id,action_type,priority_score,reason_json,expires_at)
                VALUES (?,?,?,?::recommendation_action,?,?::jsonb,now()+interval '1 day')
                """,
                userId,
                notebookId,
                conceptId,
                action,
                priority,
                Json.write(reason));
    }
}
