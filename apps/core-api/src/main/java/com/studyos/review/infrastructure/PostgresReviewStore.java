package com.studyos.review.infrastructure;

import com.studyos.review.application.port.ReviewStore;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.persistence.Rows;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresReviewStore implements ReviewStore {
    private final JdbcTemplate jdbc;
    public PostgresReviewStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Map<String,Object>> queue(UUID user,UUID notebook,int limit){
        return jdbc.query("""
                SELECT f.id AS card_id,f.deck_id,d.notebook_id,f.front,f.back,coalesce(f.due_at,f.created_at) AS due_at,
                CASE WHEN c.id IS NULL THEN NULL ELSE jsonb_build_object('id',c.id,'sourceId',s.id,'sourceTitle',s.title,
                'chunkId',c.id,'pageNo',c.page_no,'startMs',c.start_ms,'endMs',c.end_ms) END AS source_citation
                FROM flashcards f JOIN flashcard_decks d ON d.id=f.deck_id
                JOIN notebooks n ON n.id=d.notebook_id AND n.status='ACTIVE'
                JOIN workspace_members wm ON wm.workspace_id=n.workspace_id AND wm.user_id=d.user_id
                JOIN workspaces w ON w.id=n.workspace_id AND w.status='ACTIVE'
                LEFT JOIN document_chunks c ON c.id=f.source_chunk_id
                LEFT JOIN sources s ON s.current_version_id=c.source_version_id AND s.status='READY'
                WHERE d.user_id=? AND (?::uuid IS NULL OR d.notebook_id=?) AND coalesce(f.due_at,f.created_at)<=now()
                ORDER BY coalesce(f.due_at,f.created_at),f.id LIMIT ?
                """,Rows::map,user,notebook,notebook,limit);
    }
    public Optional<Map<String,Object>> card(UUID user,UUID card,boolean lock){
        return jdbc.query("""
                SELECT f.*,d.notebook_id FROM flashcards f JOIN flashcard_decks d ON d.id=f.deck_id
                WHERE f.id=? AND d.user_id=?
                """+(lock?" FOR UPDATE OF f":""),Rows::map,card,user).stream().findFirst();
    }
    public Optional<Map<String,Object>> priorReview(UUID user,String key){
        return jdbc.query("SELECT * FROM flashcard_reviews WHERE user_id=? AND idempotency_key=?",Rows::map,user,key).stream().findFirst();
    }
    public Instant lastReviewedAt(UUID user,UUID card){
        Timestamp value=jdbc.queryForObject("SELECT max(reviewed_at) FROM flashcard_reviews WHERE user_id=? AND card_id=?",Timestamp.class,user,card);
        return value==null?null:value.toInstant();
    }
    public Map<String,Object> saveReview(UUID user,UUID card,int grade,String key,Object oldState,Object newState,Instant now,Instant dueAt){
        var review=jdbc.queryForObject("""
                INSERT INTO flashcard_reviews(user_id,card_id,grade,idempotency_key,previous_state_json,new_state_json,reviewed_at,next_review_at)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?) RETURNING *
                """,Rows::map,user,card,grade,key,Json.write(oldState),Json.write(newState),Timestamp.from(now),Timestamp.from(dueAt));
        jdbc.update("UPDATE flashcards SET scheduler_state_json=?::jsonb,due_at=?,updated_at=? WHERE id=?",
                Json.write(newState),Timestamp.from(dueAt),Timestamp.from(now),card);
        return review;
    }
    public UUID vocabularyCard(UUID user,UUID notebook,UUID vocabulary,String front,String back){
        UUID deck=UUID.nameUUIDFromBytes(("vocabulary-deck:"+user+":"+notebook).getBytes(StandardCharsets.UTF_8));
        UUID card=UUID.nameUUIDFromBytes(("vocabulary-card:"+vocabulary).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO flashcard_decks(id,notebook_id,user_id,title) VALUES (?,?,?,'Từ vựng đã lưu') ON CONFLICT DO NOTHING",deck,notebook,user);
        jdbc.update("INSERT INTO flashcards(id,deck_id,front,back,due_at) VALUES (?,?,?,?,now()) ON CONFLICT DO NOTHING",card,deck,front,back);
        return card;
    }
}
