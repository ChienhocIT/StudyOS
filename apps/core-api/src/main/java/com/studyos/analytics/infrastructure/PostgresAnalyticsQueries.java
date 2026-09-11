package com.studyos.analytics.infrastructure;

import com.studyos.analytics.application.AnalyticsQueries;
import com.studyos.shared.persistence.Rows;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAnalyticsQueries implements AnalyticsQueries {
    private final JdbcTemplate jdbc;
    public PostgresAnalyticsQueries(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> overview(UUID user,UUID notebook){
        return jdbc.queryForObject("""
                WITH accessible AS (
                  SELECT n.id FROM notebooks n JOIN workspace_members m ON m.workspace_id=n.workspace_id
                  JOIN workspaces w ON w.id=n.workspace_id AND w.status='ACTIVE'
                  WHERE m.user_id=? AND n.status<>'DELETED' AND (?::uuid IS NULL OR n.id=?)
                ) SELECT
                  (SELECT count(*) FROM accessible) AS notebook_count,
                  (SELECT count(*) FROM sources s WHERE s.notebook_id IN (SELECT id FROM accessible) AND s.status='READY') AS ready_source_count,
                  (SELECT count(*) FROM quiz_attempts a JOIN quizzes q ON q.id=a.quiz_id WHERE a.user_id=? AND a.status='COMPLETED' AND q.notebook_id IN(SELECT id FROM accessible)) AS completed_quizzes,
                  (SELECT coalesce(avg(a.score),0) FROM quiz_attempts a JOIN quizzes q ON q.id=a.quiz_id WHERE a.user_id=? AND a.status='COMPLETED' AND q.notebook_id IN(SELECT id FROM accessible)) AS average_quiz_score,
                  (SELECT count(*) FROM flashcard_reviews r JOIN flashcards f ON f.id=r.card_id JOIN flashcard_decks d ON d.id=f.deck_id WHERE r.user_id=? AND d.notebook_id IN(SELECT id FROM accessible)) AS completed_reviews,
                  (SELECT count(*) FROM flashcards f JOIN flashcard_decks d ON d.id=f.deck_id WHERE d.user_id=? AND d.notebook_id IN(SELECT id FROM accessible) AND coalesce(f.due_at,f.created_at)<=now()) AS due_cards,
                  (SELECT coalesce(avg(m.mastery_score),0) FROM user_concept_mastery m JOIN concepts c ON c.id=m.concept_id WHERE m.user_id=? AND c.notebook_id IN(SELECT id FROM accessible)) AS average_mastery,
                  (SELECT count(*) FROM mastery_evidence e JOIN concepts c ON c.id=e.concept_id WHERE e.user_id=? AND c.notebook_id IN(SELECT id FROM accessible)) AS evidence_count,
                  (SELECT count(DISTINCT r.reviewed_at::date) FROM flashcard_reviews r JOIN flashcards f ON f.id=r.card_id JOIN flashcard_decks d ON d.id=f.deck_id WHERE r.user_id=? AND d.notebook_id IN(SELECT id FROM accessible) AND r.reviewed_at>=now()-interval '30 days') AS active_review_days_last30
                """,Rows::map,user,notebook,notebook,user,user,user,user,user,user,user);
    }
}
