package com.studyos;

import static org.assertj.core.api.Assertions.*;

import com.studyos.conversation.application.port.ChatStream;
import com.studyos.conversation.application.port.StreamEvents;
import com.studyos.notebook.application.NotebookService;
import com.studyos.source.application.port.ObjectStorage;
import com.studyos.workspace.application.WorkspaceService;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "spring.config.import=classpath:application.yml",
            "studyos.jwt-secret=test-signing-secret-that-has-at-least-32-bytes",
            "studyos.internal-jwt-secret=test-internal-secret-that-has-at-least-32-bytes",
            "studyos.messaging-enabled=false",
            "studyos.storage.access-key=test",
            "studyos.storage.secret-key=test"
        })
@Testcontainers
class ContainerCleanupIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", postgres::getJdbcUrl);
        p.add("spring.datasource.username", postgres::getUsername);
        p.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired NotebookService notebooks;
    @Autowired WorkspaceService workspaces;
    @MockitoBean ObjectStorage storage;
    @MockitoBean ChatStream chat;
    @MockitoBean StreamEvents streams;
    @MockitoBean RedisMessageListenerContainer listener;

    record Fixture(UUID user, UUID workspace, UUID notebook, UUID source, UUID version) {}

    @Test
    void notebookDeletionQueuesCleanupAndWithdrawsArtifactsWithoutTouchingOtherContainers() {
        var f = fixture();
        var other = fixture();
        UUID job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO artifact_jobs(id,notebook_id,user_id,artifact_type,scope_json) VALUES (?,?,?,'QUIZ',jsonb_build_object('sourceIds',jsonb_build_array(?::text)))",
                job,
                f.notebook(),
                f.user(),
                f.source().toString());
        notebooks.delete(f.user(), f.notebook());
        assertThat(status("notebooks", f.notebook())).isEqualTo("DELETED");
        assertThat(status("sources", f.source())).isEqualTo("DELETING");
        assertThat(status("artifact_jobs", job)).isEqualTo("FAILED");
        assertThat(status("sources", other.source())).isEqualTo("READY");
        assertCleanupCommand(f);
    }

    @Test
    void workspaceDeletionIncludesArchivedNotebooks() {
        var f = fixture();
        jdbc.update("UPDATE notebooks SET status='ARCHIVED' WHERE id=?", f.notebook());
        workspaces.delete(f.user(), f.workspace());
        assertThat(status("workspaces", f.workspace())).isEqualTo("DELETED");
        assertThat(status("sources", f.source())).isEqualTo("DELETING");
        assertCleanupCommand(f);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO notebooks(workspace_id,created_by,title) VALUES (?,?,'late')",
                                        f.workspace(),
                                        f.user()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentSourceCreationWaitsForParentDeletionAndIsRejected() throws Exception {
        var f = fixture();
        try (var deletion = dataSource.getConnection();
                var creation = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            deletion.setAutoCommit(false);
            try (var statement =
                    deletion.prepareStatement("UPDATE notebooks SET status='DELETED' WHERE id=?")) {
                statement.setObject(1, f.notebook());
                statement.executeUpdate();
            }
            int creationPid;
            try (var statement = creation.createStatement();
                    var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                creationPid = result.getInt(1);
            }
            var pending =
                    executor.submit(
                            () -> {
                                try (var statement =
                                        creation.prepareStatement(
                                                "INSERT INTO sources(workspace_id,notebook_id,created_by,type,title) VALUES (?,?,?,'RAW_TEXT','late')")) {
                                    statement.setObject(1, f.workspace());
                                    statement.setObject(2, f.notebook());
                                    statement.setObject(3, f.user());
                                    statement.executeUpdate();
                                    return "inserted";
                                } catch (SQLException failure) {
                                    return failure.getSQLState();
                                }
                            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline && !pending.isDone()) {
                    blocked =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT cardinality(pg_blocking_pids(?)) > 0",
                                            Boolean.class,
                                            creationPid));
                    if (blocked) break;
                    Thread.sleep(10);
                }
                assertThat(blocked).as("creator waits on the parent deletion lock").isTrue();
            } finally {
                deletion.commit();
            }
            assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo("23514");
        }
    }

    private String status(String table, UUID id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id=?", String.class, id);
    }

    private void assertCleanupCommand(Fixture f) {
        var command =
                jdbc.queryForMap(
                        "SELECT payload_json->'payload'->>'sourceVersionId' AS version,payload_json->>'workspaceId' AS workspace FROM outbox_events WHERE aggregate_id=? AND event_type='source.delete.requested.v1'",
                        f.source());
        assertThat(command.get("version")).isEqualTo(f.version().toString());
        assertThat(command.get("workspace")).isEqualTo(f.workspace().toString());
    }

    private Fixture fixture() {
        UUID user = UUID.randomUUID(),
                workspace = UUID.randomUUID(),
                notebook = UUID.randomUUID(),
                source = UUID.randomUUID(),
                version = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(id,email,display_name) VALUES (?,?,?)",
                user,
                user + "@cleanup.test",
                "Cleanup");
        jdbc.update(
                "INSERT INTO workspaces(id,owner_id,name) VALUES (?,?,'Cleanup')", workspace, user);
        jdbc.update(
                "INSERT INTO workspace_members(workspace_id,user_id,role) VALUES (?,?,'OWNER')",
                workspace,
                user);
        jdbc.update(
                "INSERT INTO notebooks(id,workspace_id,created_by,title) VALUES (?,?,?,'Cleanup')",
                notebook,
                workspace,
                user);
        jdbc.update(
                "INSERT INTO sources(id,workspace_id,notebook_id,created_by,type,title,status) VALUES (?,?,?,?,'RAW_TEXT','Cleanup','READY')",
                source,
                workspace,
                notebook,
                user);
        jdbc.update(
                "INSERT INTO source_versions(id,source_id,version_no,object_key) VALUES (?,?,1,?)",
                version,
                source,
                workspace + "/private.txt");
        jdbc.update("UPDATE sources SET current_version_id=? WHERE id=?", version, source);
        return new Fixture(user, workspace, notebook, source, version);
    }
}
