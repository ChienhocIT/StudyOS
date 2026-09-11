package com.studyos.language.infrastructure;

import com.studyos.language.application.port.LanguageStore;
import com.studyos.shared.persistence.Rows;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresLanguageStore implements LanguageStore {
    private final JdbcTemplate jdbc;
    public PostgresLanguageStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean validSegment(UUID source,UUID segment,String text){return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM transcript_segments t JOIN sources s ON s.current_version_id=t.source_version_id
                WHERE s.id=? AND t.id=? AND (?::text IS NULL OR t.text=?))
                """,Boolean.class,source,segment,text,text));}
    public List<Map<String,Object>> vocabulary(UUID user,UUID notebook){return jdbc.query("""
                SELECT v.* FROM vocabulary_items v JOIN notebooks n ON n.id=v.notebook_id AND n.status<>'DELETED'
                JOIN workspace_members m ON m.workspace_id=n.workspace_id AND m.user_id=v.user_id
                JOIN workspaces w ON w.id=n.workspace_id AND w.status='ACTIVE'
                WHERE v.user_id=? AND (?::uuid IS NULL OR v.notebook_id=?) ORDER BY v.created_at DESC,v.id LIMIT 300
                """,Rows::map,user,notebook,notebook);}
    public Map<String,Object> save(UUID user,UUID notebook,UUID source,UUID segment,String term,String normalized,String meaning,String context,String sourceLanguage,String targetLanguage){
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);
        var existing=jdbc.query("""
                SELECT * FROM vocabulary_items WHERE user_id=? AND notebook_id=? AND normalized_term=?
                AND coalesce(source_language,'')=coalesce(?,'') AND coalesce(target_language,'')=coalesce(?,'')
                """,Rows::map,user,notebook,normalized,sourceLanguage,targetLanguage);
        if(!existing.isEmpty())return existing.getFirst();
        return jdbc.queryForObject("""
                INSERT INTO vocabulary_items(user_id,notebook_id,source_id,transcript_segment_id,term,normalized_term,meaning,context,source_language,target_language)
                VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING *
                """,Rows::map,user,notebook,source,segment,term,normalized,meaning,context,sourceLanguage,targetLanguage);
    }
}
