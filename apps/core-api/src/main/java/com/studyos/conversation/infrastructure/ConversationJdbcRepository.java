package com.studyos.conversation.infrastructure;
import com.studyos.conversation.application.port.ConversationRepository;import com.studyos.shared.persistence.*;import org.springframework.stereotype.Repository;import org.springframework.jdbc.core.simple.JdbcClient;import java.util.*;
@Repository
public class ConversationJdbcRepository implements ConversationRepository{
    private final JdbcClient db;public ConversationJdbcRepository(JdbcClient db){this.db=db;}
    public UUID create(UUID user,UUID workspace,UUID notebook,String mode,String title){UUID id=UUID.randomUUID();db.sql("insert into conversations(id,user_id,workspace_id,notebook_id,mode,title) values(:id,:u,:w,:n,cast(:mode as conversation_mode),:title)").param("id",id).param("u",user).param("w",workspace).param("n",notebook).param("mode",mode).param("title",title).update();return id;}
    public Optional<Map<String,Object>> get(UUID id,boolean lock){return db.sql("select * from conversations where id=:id "+(lock?"for update":"")).param("id",id).query(Rows::map).optional();}
    public List<Map<String,Object>> list(UUID user,UUID notebook){return db.sql("select * from conversations where user_id=:u and notebook_id=:n order by updated_at desc").param("u",user).param("n",notebook).query(Rows::map).list();}
    public List<Map<String,Object>> messages(UUID conversation,long after){return db.sql("select * from messages where conversation_id=:id and sequence_no>:seq order by sequence_no limit 1000").param("id",conversation).param("seq",after).query(Rows::map).list();}
    public List<Map<String,Object>> citations(UUID message){return db.sql("select c.id,c.citation_key as key,d.id as chunk_id,s.id as source_id,s.title as source_title,d.page_no,d.start_ms,d.end_ms,d.text as quote from citations c join document_chunks d on d.id=c.chunk_id join source_versions v on v.id=d.source_version_id join sources s on s.id=v.source_id where c.message_id=:id order by c.rank").param("id",message).query(Rows::map).list();}
    public Optional<Map<String,Object>> generation(UUID conversation,UUID request,boolean lock){return db.sql("select * from conversation_generations where conversation_id=:c and request_id=:r "+(lock?"for update":"")).param("c",conversation).param("r",request).query(Rows::map).optional();}
    public long activeCount(UUID user){return db.sql("select count(*) from conversation_generations where user_id=:u and status='STREAMING'").param("u",user).query(Long.class).single();}
    public void lockUser(UUID user){db.sql("select pg_advisory_xact_lock(hashtextextended(:u,1))").param("u",user.toString()).query(rs->{rs.next();return true;});}
    public UUID begin(UUID conversation,UUID request,UUID user,String content,List<UUID> sources,String trace){
        long seq=db.sql("select coalesce(max(sequence_no),0) from messages where conversation_id=:id").param("id",conversation).query(Long.class).single();
        UUID assistant=UUID.randomUUID();
        db.sql("insert into messages(conversation_id,request_id,role,content,sequence_no,trace_id) values(:c,:r,'USER',:content,:s,:trace)").param("c",conversation).param("r",request).param("content",content).param("s",seq+1).param("trace",trace).update();
        db.sql("insert into messages(id,conversation_id,role,status,sequence_no,trace_id) values(:id,:c,'ASSISTANT','STREAMING',:s,:trace)").param("id",assistant).param("c",conversation).param("s",seq+2).param("trace",trace).update();
        db.sql("insert into conversation_generations(conversation_id,request_id,assistant_message_id,user_id,source_ids_json) values(:c,:r,:a,:u,cast(:sources as jsonb))").param("c",conversation).param("r",request).param("a",assistant).param("u",user).param("sources",Json.write(sources)).update();
        db.sql("update conversations set updated_at=now() where id=:id").param("id",conversation).update();return assistant;
    }
    public void finish(UUID conversation,UUID request,String content,String status,String grounding){
        db.sql("update messages set content=:content,status=cast(:status as message_status),grounding_status=:g where id=(select assistant_message_id from conversation_generations where conversation_id=:c and request_id=:r) and status='STREAMING'").param("content",content).param("status",status).param("g",grounding).param("c",conversation).param("r",request).update();
        db.sql("update conversation_generations set status=:status,finished_at=now() where conversation_id=:c and request_id=:r and status='STREAMING'").param("status",status).param("c",conversation).param("r",request).update();
    }
    public Optional<Map<String,Object>> citationChunk(UUID chunk,UUID workspace,UUID notebook,List<UUID> sources){if(sources.isEmpty())return Optional.empty();return db.sql("select d.id,d.text,s.id as source_id from document_chunks d join sources s on s.current_version_id=d.source_version_id where d.id=:id and d.workspace_id=:w and d.notebook_id=:n and s.status='READY' and s.id in (:sources)").param("id",chunk).param("w",workspace).param("n",notebook).param("sources",sources).query(Rows::map).optional();}
    public UUID citation(UUID message,UUID chunk,String key,int rank){UUID id=UUID.randomUUID();db.sql("insert into citations(id,message_id,chunk_id,citation_key,rank) values(:id,:m,:c,:k,:r)").param("id",id).param("m",message).param("c",chunk).param("k",key).param("r",rank).update();return id;}
    public Optional<Map<String,Object>> message(UUID id){return db.sql("select * from messages where id=:id").param("id",id).query(Rows::map).optional();}
    public void feedback(UUID user,UUID message,int rating,String reason,String comment){db.sql("insert into message_feedback(user_id,message_id,rating,reason,comment) values(:u,:m,:r,:reason,:comment) on conflict(message_id,user_id) do update set rating=excluded.rating,reason=excluded.reason,comment=excluded.comment").param("u",user).param("m",message).param("r",rating).param("reason",reason).param("comment",comment).update();}
    public void expireStale(){db.sql("update messages set status='FAILED' where id in (select assistant_message_id from conversation_generations where status='STREAMING' and created_at<now()-interval '5 minutes')").update();db.sql("update conversation_generations set status='FAILED',finished_at=now() where status='STREAMING' and created_at<now()-interval '5 minutes'").update();}
}

