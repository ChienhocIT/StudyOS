package com.studyos.notebook.infrastructure;
import com.studyos.notebook.application.port.NotebookRepository;import com.studyos.shared.persistence.Rows;
import org.springframework.stereotype.Repository;import org.springframework.jdbc.core.simple.JdbcClient;import java.util.*;
@Repository
public class NotebookJdbcRepository implements NotebookRepository{
    private final JdbcClient db;public NotebookJdbcRepository(JdbcClient db){this.db=db;}
    public Optional<Map<String,Object>> get(UUID id){return db.sql("select * from notebooks where id=:id and status<>'DELETED'").param("id",id).query(Rows::map).optional();}
    public List<Map<String,Object>> list(UUID workspace){return db.sql("select * from notebooks where workspace_id=:w and status<>'DELETED' order by updated_at desc,id").param("w",workspace).query(Rows::map).list();}
    public UUID create(UUID user,UUID workspace,String title,String description,String goal){UUID id=UUID.randomUUID();db.sql("insert into notebooks(id,workspace_id,created_by,title,description,goal_text) values(:id,:w,:u,:t,:d,:g)").param("id",id).param("w",workspace).param("u",user).param("t",title).param("d",description).param("g",goal).update();return id;}
    public void update(UUID id,String title,String description,String goal,String status){db.sql("update notebooks set title=coalesce(:t,title),description=coalesce(:d,description),goal_text=coalesce(:g,goal_text),status=coalesce(cast(:s as notebook_status),status),updated_at=now() where id=:id").param("id",id).param("t",title).param("d",description).param("g",goal).param("s",status).update();}
    public void delete(UUID id){db.sql("update notebooks set status='DELETED',updated_at=now() where id=:id").param("id",id).update();}
}

