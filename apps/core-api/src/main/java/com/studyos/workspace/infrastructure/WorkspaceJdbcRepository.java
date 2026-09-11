package com.studyos.workspace.infrastructure;

import com.studyos.shared.persistence.Rows;
import com.studyos.workspace.application.port.WorkspaceRepository;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceJdbcRepository implements WorkspaceRepository {
    private final JdbcClient db;

    public WorkspaceJdbcRepository(JdbcClient db) {
        this.db = db;
    }

    private static final String SELECT =
            "select w.id,w.name,w.plan,w.created_at,w.updated_at,m.role from workspaces w join workspace_members m on m.workspace_id=w.id where w.status='ACTIVE' and m.user_id=:user";

    public List<Map<String, Object>> list(UUID user) {
        return db.sql(SELECT + " order by w.created_at")
                .param("user", user)
                .query(Rows::map)
                .list();
    }

    public Optional<Map<String, Object>> get(UUID user, UUID workspace) {
        return db.sql(SELECT + " and w.id=:id")
                .param("user", user)
                .param("id", workspace)
                .query(Rows::map)
                .optional();
    }

    public UUID create(UUID user, String name) {
        UUID id = UUID.randomUUID();
        db.sql("insert into workspaces(id,owner_id,name) values(:id,:user,:name)")
                .param("id", id)
                .param("user", user)
                .param("name", name)
                .update();
        db.sql("insert into workspace_members(workspace_id,user_id,role) values(:id,:user,'OWNER')")
                .param("id", id)
                .param("user", user)
                .update();
        return id;
    }

    public void rename(UUID id, String name) {
        db.sql("update workspaces set name=:name,updated_at=now() where id=:id")
                .param("name", name)
                .param("id", id)
                .update();
    }

    public void delete(UUID id) {
        db.sql("update workspaces set status='DELETED',updated_at=now() where id=:id")
                .param("id", id)
                .update();
    }

    public List<Map<String, Object>> members(UUID id) {
        return db.sql(
                        "select m.user_id,u.email,u.display_name,m.role,m.joined_at from workspace_members m join users u on u.id=m.user_id where m.workspace_id=:id order by m.joined_at")
                .param("id", id)
                .query(Rows::map)
                .list();
    }
}
