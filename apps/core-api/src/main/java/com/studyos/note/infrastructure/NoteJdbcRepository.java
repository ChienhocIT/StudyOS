package com.studyos.note.infrastructure;
import com.studyos.note.application.port.NoteRepository;import com.studyos.shared.persistence.Rows;import org.springframework.stereotype.Repository;import org.springframework.jdbc.core.simple.JdbcClient;import java.util.*;
@Repository
public class NoteJdbcRepository implements NoteRepository{
    private final JdbcClient db;public NoteJdbcRepository(JdbcClient db){this.db=db;}
    public Optional<Map<String,Object>> get(UUID id){return db.sql("select * from notes where id=:id").param("id",id).query(Rows::map).optional();}
    public List<Map<String,Object>> list(UUID user,UUID notebook){return db.sql("select * from notes where user_id=:u and notebook_id=:n order by updated_at desc").param("u",user).param("n",notebook).query(Rows::map).list();}
    public UUID create(UUID user,UUID workspace,UUID notebook,String title,String content){UUID id=UUID.randomUUID();db.sql("insert into notes(id,workspace_id,notebook_id,user_id,title,content) values(:id,:w,:n,:u,:t,:c)").param("id",id).param("w",workspace).param("n",notebook).param("u",user).param("t",title).param("c",content).update();return id;}
    public void update(UUID id,String title,String content){db.sql("update notes set title=coalesce(:t,title),content=coalesce(:c,content),updated_at=now() where id=:id").param("t",title).param("c",content).param("id",id).update();}
    public void delete(UUID id){db.sql("delete from notes where id=:id").param("id",id).update();}
    public List<UUID> citations(UUID id){return db.sql("select citation_id from note_citations where note_id=:id order by citation_id").param("id",id).query(UUID.class).list();}
    public boolean citationAllowed(UUID id,UUID user,UUID notebook){return db.sql("select count(*) from citations c join messages m on m.id=c.message_id join conversations cv on cv.id=m.conversation_id where c.id=:id and cv.user_id=:u and cv.notebook_id=:n").param("id",id).param("u",user).param("n",notebook).query(Long.class).single()==1;}
    public void link(UUID note,List<UUID> citations){db.sql("delete from note_citations where note_id=:id").param("id",note).update();for(UUID id:citations)db.sql("insert into note_citations(note_id,citation_id) values(:note,:id)").param("note",note).param("id",id).update();}
}

