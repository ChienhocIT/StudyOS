package com.studyos.source.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyos.learning.application.ConceptAcceptance;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.shared.outbox.Outbox;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.port.ObjectStorage;
import com.studyos.source.infrastructure.SourceJdbcRepository;
import com.studyos.studio.application.SourceArtifactInvalidation;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SourceRetryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16")
                            .asCompatibleSubstituteFor("postgres"));

    private JdbcClient db;
    private TransactionTemplate tx;
    private SourceJdbcRepository repo;
    private SourceService service;
    private SourceEventService events;
    private Outbox outbox;
    private NotebookAccess notebooks;
    private final UUID user = UUID.randomUUID(),
            workspace = UUID.randomUUID(),
            notebook = UUID.randomUUID(),
            source = UUID.randomUUID(),
            original = UUID.randomUUID();

    @BeforeEach
    void setup() {
        var dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        db = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repo = new SourceJdbcRepository(db);
        notebooks = mock(NotebookAccess.class);
        outbox = mock(Outbox.class);
        service =
                new SourceService(
                        repo,
                        notebooks,
                        mock(ObjectStorage.class),
                        outbox,
                        mock(SourceArtifactInvalidation.class));
        events = new SourceEventService(repo, mock(ConceptAcceptance.class));
        tx.executeWithoutResult(
                status -> {
                    db.sql(
                                    "insert into users(id,email,display_name) values(:u,:email,'Retry tester')")
                            .param("u", user)
                            .param("email", user + "@test.local")
                            .update();
                    db.sql(
                                    "insert into workspaces(id,owner_id,name) values(:w,:u,'Retry workspace')")
                            .param("w", workspace)
                            .param("u", user)
                            .update();
                    db.sql(
                                    "insert into notebooks(id,workspace_id,created_by,title) values(:n,:w,:u,'Retry notebook')")
                            .param("n", notebook)
                            .param("w", workspace)
                            .param("u", user)
                            .update();
                    repo.create(
                            user,
                            workspace,
                            notebook,
                            "RAW_TEXT",
                            "Original",
                            null,
                            "text/plain",
                            7,
                            "a".repeat(64),
                            null,
                            "FAILED",
                            source,
                            original,
                            "immutable/original.txt");
                    repo.status(source, "FAILED", "TRANSIENT", "Try again", true);
                    repo.parsed(original, "old/normalized.json", "old-parser");
                });
    }

    @Test
    void retryCreatesCleanVersionAndLateFactsCannotRegressIt() {
        var response = tx.execute(status -> service.retry(user, source, "retry-1"));
        var fresh = repo.get(source, false).orElseThrow();
        UUID version = Rows.uuid(fresh, "currentVersionId");
        assertThat(version).isNotEqualTo(original);
        assertThat(fresh)
                .containsEntry("versionNo", 2)
                .containsEntry("status", "QUEUED")
                .containsEntry("objectKey", "immutable/original.txt")
                .containsEntry("checksumSha256", "a".repeat(64));
        var parser =
                db.sql(
                                "select parse_status::text,normalized_object_key,parser_version from source_versions where id=:v")
                        .param("v", version)
                        .query(Rows::map)
                        .single();
        assertThat(parser)
                .containsEntry("parseStatus", "PENDING")
                .containsEntry("normalizedObjectKey", null)
                .containsEntry("parserVersion", null);
        verify(outbox)
                .publish(
                        eq(workspace),
                        eq("Source"),
                        eq(source),
                        eq("source.parse.requested.v1"),
                        argThat(payload -> version.equals(payload.get("sourceVersionId"))));

        for (String type :
                List.of("source.processing.failed.v1", "source.parsed.v1", "source.ready.v1")) {
            tx.executeWithoutResult(status -> events.accept(fact(original, type)));
        }
        assertThat(repo.get(source, false).orElseThrow()).containsEntry("status", "QUEUED");
        tx.executeWithoutResult(
                status -> events.accept(fact(version, "source.processing.failed.v1")));
        assertThat(repo.get(source, false).orElseThrow()).containsEntry("status", "FAILED");

        // Same key remains a replay even after the attempt failed, rather than silently retrying
        // it.
        var replay = tx.execute(status -> service.retry(user, source, "retry-1"));
        assertThat(replay).isEqualTo(Json.object(Json.write(response)));
        assertThat(repo.get(source, false).orElseThrow())
                .containsEntry("currentVersionId", version);
        verify(outbox, times(1)).publish(any(), any(), any(), any(), any());
        tx.executeWithoutResult(status -> service.retry(user, source, "retry-2"));
        assertThat(repo.get(source, false).orElseThrow())
                .containsEntry("versionNo", 3)
                .containsEntry("status", "QUEUED");
    }

    @Test
    void replayRequiresCurrentAuthorizationAndKeylessRetryCannotDuplicateWork() {
        tx.executeWithoutResult(status -> service.retry(user, source, "key"));
        assertThatThrownBy(() -> tx.execute(status -> service.retry(user, source)))
                .isInstanceOf(ApiException.class);
        when(notebooks.requireWrite(user, notebook))
                .thenThrow(ApiException.notFound("NOTEBOOK_NOT_FOUND", "Notebook not found."));
        assertThatThrownBy(() -> tx.execute(status -> service.retry(user, source, "key")))
                .isInstanceOf(ApiException.class);
        verify(outbox, times(1)).publish(any(), any(), any(), any(), any());
        assertThat(
                        db.sql("select count(*) from source_versions where source_id=:s")
                                .param("s", source)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    private Map<String, Object> fact(UUID version, String type) {
        return Map.of(
                "eventId",
                UUID.randomUUID(),
                "eventVersion",
                1,
                "eventType",
                type,
                "workspaceId",
                workspace,
                "payload",
                Map.of(
                        "sourceId",
                        source,
                        "sourceVersionId",
                        version,
                        "retryable",
                        true,
                        "errorCode",
                        "TRANSIENT"));
    }
}
