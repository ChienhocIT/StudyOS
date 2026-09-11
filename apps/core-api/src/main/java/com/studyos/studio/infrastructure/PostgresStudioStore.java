package com.studyos.studio.infrastructure;

import com.studyos.studio.application.port.StudioStore;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.persistence.Rows;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresStudioStore implements StudioStore {
    private final JdbcTemplate jdbc;
    public PostgresStudioStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Map<String,Object>> jobByKey(UUID user,String key){
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);
        return jdbc.query("SELECT * FROM artifact_jobs WHERE user_id=? AND idempotency_key=?",Rows::map,user,key).stream().findFirst();
    }
    public Map<String,Object> createJob(UUID user,UUID notebook,String type,String key,String fingerprint,Object scope,Object options){
        return jdbc.queryForObject("""
                INSERT INTO artifact_jobs(user_id,notebook_id,artifact_type,idempotency_key,request_fingerprint,scope_json,options_json)
                VALUES (?,?,?::artifact_type,?,?,?::jsonb,?::jsonb) RETURNING *
                """,Rows::map,user,notebook,type,key,fingerprint,Json.write(scope),Json.write(options));
    }
    public Optional<Map<String,Object>> job(UUID user,UUID id,boolean lock){return jdbc.query("SELECT * FROM artifact_jobs WHERE user_id=? AND id=?"+(lock?" FOR UPDATE":""),Rows::map,user,id).stream().findFirst();}
    public Optional<Map<String,Object>> jobForEvent(UUID workspace,UUID id){return jdbc.query("""
                SELECT j.*,n.workspace_id FROM artifact_jobs j JOIN notebooks n ON n.id=j.notebook_id
                JOIN workspaces w ON w.id=n.workspace_id AND w.status='ACTIVE'
                JOIN workspace_members m ON m.workspace_id=n.workspace_id AND m.user_id=j.user_id
                WHERE j.id=? AND n.workspace_id=? AND n.status='ACTIVE' FOR UPDATE OF j
                """,Rows::map,id,workspace).stream().findFirst();}
    public void completeJob(UUID id,UUID result,Object resultJson){jdbc.update("UPDATE artifact_jobs SET status='SUCCEEDED',result_ref=?,result_json=?::jsonb,error_code=NULL,updated_at=now() WHERE id=?",result,Json.write(resultJson),id);}
    public void failJob(UUID id,String code){jdbc.update("UPDATE artifact_jobs SET status='FAILED',error_code=?,updated_at=now() WHERE id=?",code,id);}
    public boolean claimEvent(UUID event){return jdbc.update("INSERT INTO processed_events(consumer_name,event_id) VALUES ('core-artifact-v1',?) ON CONFLICT DO NOTHING",event)>0;}
    public boolean validChunk(UUID workspace,UUID notebook,UUID chunk,List<UUID> sourceIds){
        var rows=jdbc.query("""
                SELECT s.id FROM document_chunks c JOIN sources s ON s.current_version_id=c.source_version_id
                WHERE c.id=? AND c.workspace_id=? AND c.notebook_id=? AND s.workspace_id=c.workspace_id
                AND s.notebook_id=c.notebook_id AND s.status='READY'
                """,(rs,i)->rs.getObject(1,UUID.class),chunk,workspace,notebook);
        return rows.size()==1 && sourceIds.contains(rows.getFirst());
    }
    public List<UUID> chunkConcepts(UUID notebook,List<UUID> chunks){
        Set<UUID> ids=new TreeSet<>();
        for(UUID chunk:chunks) ids.addAll(jdbc.query("SELECT c.id FROM concepts c JOIN chunk_concepts cc ON cc.concept_id=c.id WHERE cc.chunk_id=? AND c.notebook_id=? ORDER BY c.id",(rs,i)->rs.getObject(1,UUID.class),chunk,notebook));
        return List.copyOf(ids);
    }
    public UUID saveQuiz(UUID user,UUID notebook,UUID job,String title,List<Map<String,Object>> questions){
        UUID id=jdbc.queryForObject("INSERT INTO quizzes(user_id,notebook_id,artifact_job_id,title) VALUES (?,?,?,?) RETURNING id",UUID.class,user,notebook,job,title);
        int ordinal=0;
        for(var question:questions){
            UUID q=jdbc.queryForObject("""
                    INSERT INTO quiz_questions(quiz_id,ordinal,question_type,prompt,answer_json,explanation,source_refs_json)
                    VALUES (?,?,?::question_type,?,?::jsonb,?,?::jsonb) RETURNING id
                    """,UUID.class,id,++ordinal,question.get("type"),question.get("prompt"),Json.write(question.get("answer")),question.get("explanation"),Json.write(question.get("sourceRefs")));
            for(Object concept:(List<?>)question.get("conceptIds"))jdbc.update("INSERT INTO quiz_question_concepts(question_id,concept_id) VALUES (?,?)",q,concept);
        }
        return id;
    }
    public UUID saveDeck(UUID user,UUID notebook,UUID job,String title,List<Map<String,Object>> cards){
        UUID deck=jdbc.queryForObject("INSERT INTO flashcard_decks(user_id,notebook_id,artifact_job_id,title) VALUES (?,?,?,?) RETURNING id",UUID.class,user,notebook,job,title);
        for(var card:cards)jdbc.update("INSERT INTO flashcards(deck_id,front,back,source_chunk_id,concept_id,due_at) VALUES (?,?,?,?,?,now())",deck,card.get("front"),card.get("back"),card.get("sourceChunkId"),card.get("conceptId"));
        return deck;
    }
    public Optional<Map<String,Object>> quiz(UUID user,UUID id){return jdbc.query("SELECT * FROM quizzes WHERE id=? AND user_id=? AND status='READY'",Rows::map,id,user).stream().findFirst();}
    public List<Map<String,Object>> questions(UUID quiz){return jdbc.query("""
                SELECT q.*,coalesce((SELECT jsonb_agg(c.concept_id ORDER BY c.concept_id) FROM quiz_question_concepts c WHERE c.question_id=q.id),'[]'::jsonb) AS concept_ids
                FROM quiz_questions q WHERE q.quiz_id=? ORDER BY q.ordinal
                """,Rows::map,quiz);}
    public Map<String,Object> startAttempt(UUID user,UUID quiz){return jdbc.queryForObject("INSERT INTO quiz_attempts(user_id,quiz_id) VALUES (?,?) RETURNING *",Rows::map,user,quiz);}
    public Optional<Map<String,Object>> attempt(UUID user,UUID attempt,boolean lock){return jdbc.query("""
                SELECT a.*,q.notebook_id FROM quiz_attempts a JOIN quizzes q ON q.id=a.quiz_id
                WHERE a.id=? AND a.user_id=?
                """+(lock?" FOR UPDATE OF a":""),Rows::map,attempt,user).stream().findFirst();}
    public void saveAnswer(UUID attempt,UUID question,Object answer){jdbc.update("""
                INSERT INTO quiz_answers(attempt_id,question_id,answer_json) VALUES (?,?,?::jsonb)
                ON CONFLICT(attempt_id,question_id) DO UPDATE SET answer_json=excluded.answer_json,answered_at=now()
                """,attempt,question,Json.write(answer));}
    public List<Map<String,Object>> answers(UUID attempt){return jdbc.query("SELECT * FROM quiz_answers WHERE attempt_id=?",Rows::map,attempt);}
    public void scoreAnswer(UUID attempt,UUID question,boolean correct){jdbc.update("UPDATE quiz_answers SET correctness=?,score=? WHERE attempt_id=? AND question_id=?",correct,correct?BigDecimal.ONE:BigDecimal.ZERO,attempt,question);}
    public void completeAttempt(UUID attempt,BigDecimal score){jdbc.update("UPDATE quiz_attempts SET status='COMPLETED',score=?,completed_at=now() WHERE id=?",score,attempt);}
}
