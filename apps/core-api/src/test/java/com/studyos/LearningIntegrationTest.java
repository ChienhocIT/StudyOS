package com.studyos;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.studyos.conversation.application.port.*;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.usage.AiUsagePolicy;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.port.ObjectStorage;
import com.studyos.studio.application.SourceArtifactInvalidation;
import com.studyos.studio.application.StudioService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
@AutoConfigureMockMvc
@Testcontainers
class LearningIntegrationTest {
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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired StudioService studio;
    @Autowired SourceArtifactInvalidation invalidation;
    @Autowired AiUsagePolicy usage;
    @Autowired com.studyos.learning.application.LearningService learning;
    @Autowired com.studyos.analytics.application.AnalyticsQueries analytics;
    @Autowired com.studyos.notebook.application.port.NotebookRepository notebookRepository;
    @MockitoBean ObjectStorage storage;
    @MockitoBean ChatStream chat;
    @MockitoBean StreamEvents streams;
    @MockitoBean RedisMessageListenerContainer listener;

    record Fixture(
            UUID user,
            String token,
            UUID workspace,
            UUID notebook,
            UUID source,
            UUID chunk,
            UUID concept) {}

    @Test
    void goalValidationRejectsMalformedChangesBeforeWritingAndSupportsClearing() throws Exception {
        var f = fixture();
        var created =
                call(
                        "POST",
                        "/learning/goals",
                        f.token(),
                        null,
                        Map.of(
                                "workspaceId",
                                f.workspace(),
                                "title",
                                "  Learn databases  ",
                                "weeklyMinutes",
                                60,
                                "targetDate",
                                "2026-12-31"),
                        201);
        String path = "/learning/goals/" + created.get("id");
        for (String patchBody :
                List.of(
                        "{\"status\":null}",
                        "{\"targetDate\":\"2026-02-30\"}",
                        "{\"weeklyMinutes\":15.5}",
                        "{\"weeklyMinutes\":4294967356}",
                        "{\"description\":{}}",
                        "{\"title\":\"Changed\",\"unexpected\":true}")) {
            mvc.perform(
                            patch("/api/v1" + path)
                                    .header("Authorization", "Bearer " + f.token())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(patchBody))
                    .andExpect(status().isBadRequest());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM learning_goals WHERE id=?",
                                String.class,
                                UUID.fromString(created.get("id").toString())))
                .isEqualTo("Learn databases");
        var clear = new LinkedHashMap<String, Object>();
        clear.put("weeklyMinutes", null);
        clear.put("targetDate", null);
        clear.put("description", null);
        var changed = call("PATCH", path, f.token(), null, clear, 200);
        assertThat(changed.get("weeklyMinutes")).isNull();
        assertThat(changed.get("targetDate")).isNull();
        call(
                "POST",
                "/learning/goals",
                f.token(),
                null,
                Map.of("workspaceId", f.workspace(), "title", "Goal", "targetDate", "invalid"),
                400);
        call(
                "POST",
                "/learning/goals",
                f.token(),
                null,
                Map.of("workspaceId", f.workspace(), "title", "Goal", "weeklyMinutes", 15.5),
                400);
    }

    @Test
    void recommendationsExcludeWithdrawnConceptEvidenceAndStaleDecks() throws Exception {
        var f = fixture();
        generated(f, "FLASHCARDS");
        assertThat(learning.recommendations(f.user())).isNotEmpty();
        invalidation.invalidate(f.workspace(), f.notebook(), f.source());
        jdbc.update("UPDATE sources SET status='DELETING' WHERE id=?", f.source());
        assertThat(learning.recommendations(f.user())).isEmpty();
        assertThat(
                        ((Number) analytics.overview(f.user(), f.notebook()).get("dueCards"))
                                .longValue())
                .isZero();
    }

    @Test
    void archivedNotebookTerminatesPendingArtifactAndUsageOnResultArrival() throws Exception {
        var f = fixture();
        var job =
                studio.generate(
                        f.user(),
                        f.notebook(),
                        "QUIZ",
                        UUID.randomUUID().toString(),
                        List.of(f.source()),
                        Map.of("count", 1));
        UUID id = UUID.fromString(job.get("id").toString());
        jdbc.update("UPDATE notebooks SET status='ARCHIVED' WHERE id=?", f.notebook());
        var event =
                Map.<String, Object>of(
                        "eventId",
                        UUID.randomUUID(),
                        "eventVersion",
                        1,
                        "workspaceId",
                        f.workspace(),
                        "eventType",
                        "quiz.generated.v1",
                        "payload",
                        Map.of(
                                "artifactJobId",
                                id,
                                "notebookId",
                                f.notebook(),
                                "userId",
                                f.user(),
                                "artifactType",
                                "QUIZ"));
        studio.acceptEvent(event);
        studio.acceptEvent(event);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT error_code FROM artifact_jobs WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("ARTIFACT_SCOPE_REVOKED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ai_usage_reservations WHERE request_key=?",
                                String.class,
                                id.toString()))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM quizzes WHERE artifact_job_id=?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void staleNotebookUpdateCannotResurrectDeletedNotebook() throws Exception {
        var f = fixture();
        assertThat(notebookRepository.get(f.notebook())).isPresent();
        notebookRepository.delete(f.notebook());
        assertThatThrownBy(
                        () ->
                                notebookRepository.update(
                                        f.notebook(), "Resurrect", null, null, "ACTIVE"))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status::text FROM notebooks WHERE id=?",
                                String.class,
                                f.notebook()))
                .isEqualTo("DELETED");
    }

    @Test
    void malformedOptionalTelemetryDoesNotLoseCompletedBusinessUsage() throws Exception {
        var f = fixture();
        String key = UUID.randomUUID().toString();
        usage.reserve(f.user(), f.workspace(), "LANGUAGE", key);
        usage.complete(
                f.user(),
                "LANGUAGE",
                key,
                Map.of(
                        "model",
                        "provider",
                        "inputTokens",
                        1.5,
                        "outputTokens",
                        -2,
                        "estimatedCostUsd",
                        "not-a-price"),
                "x".repeat(100));
        var run =
                jdbc.queryForMap(
                        "SELECT input_tokens,output_tokens,estimated_cost_usd,metadata_json FROM ai_runs WHERE user_id=?",
                        f.user());
        assertThat(run.get("input_tokens")).isNull();
        assertThat(run.get("output_tokens")).isNull();
        assertThat(run.get("estimated_cost_usd")).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ai_usage_reservations WHERE request_key=?",
                                String.class,
                                key))
                .isEqualTo("COMPLETED");
    }

    @Test
    void failedOutboxDeliveryBacksOffWithoutBlockingOtherEvents() {
        var rabbit =
                org.mockito.Mockito.mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        UUID failed = UUID.randomUUID(), delivered = UUID.randomUUID();
        for (UUID id : List.of(failed, delivered))
            jdbc.update(
                    "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload_json,occurred_at,next_publish_at) VALUES (?,'TEST',?,'mastery.updated.v1','{}',now()-interval '1 day',now()-interval '1 day')",
                    id,
                    id);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            var correlation =
                                    invocation.getArgument(
                                            3,
                                            org.springframework.amqp.rabbit.connection
                                                    .CorrelationData.class);
                            if (correlation.getId().equals(failed.toString()))
                                throw new IllegalStateException("Broker rejected delivery");
                            correlation
                                    .getFuture()
                                    .complete(
                                            new org.springframework.amqp.rabbit.connection
                                                    .CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbit)
                .send(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(
                                org.springframework.amqp.core.Message.class),
                        org.mockito.ArgumentMatchers.any(
                                org.springframework.amqp.rabbit.connection.CorrelationData.class));
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                dataSource));
        var relay =
                new com.studyos.shared.outbox.OutboxRelay(
                        org.springframework.jdbc.core.simple.JdbcClient.create(dataSource),
                        rabbit,
                        transaction);
        relay.relay();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_at IS NOT NULL FROM outbox_events WHERE id=?",
                                Boolean.class,
                                delivered))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_at IS NULL AND next_publish_at>now() FROM outbox_events WHERE id=?",
                                Boolean.class,
                                failed))
                .isTrue();
        relay.relay();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT publish_attempts FROM outbox_events WHERE id=?",
                                Integer.class,
                                failed))
                .isEqualTo(1);
    }

    Fixture fixture() throws Exception {
        var registration =
                call(
                        "POST",
                        "/auth/register",
                        null,
                        null,
                        Map.of(
                                "email",
                                UUID.randomUUID() + "@example.com",
                                "password",
                                "Testing123!",
                                "displayName",
                                "Learner"),
                        201);
        UUID user = UUID.fromString(((Map<?, ?>) registration.get("user")).get("id").toString());
        String token = ((Map<?, ?>) registration.get("tokens")).get("accessToken").toString();
        UUID workspace =
                jdbc.queryForObject("SELECT id FROM workspaces WHERE owner_id=?", UUID.class, user);
        UUID notebook =
                UUID.fromString(
                        call(
                                        "POST",
                                        "/workspaces/" + workspace + "/notebooks",
                                        token,
                                        null,
                                        Map.of("title", "Database principles"),
                                        201)
                                .get("id")
                                .toString());
        UUID source = UUID.randomUUID(),
                version = UUID.randomUUID(),
                chunk = UUID.randomUUID(),
                concept = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sources(id,workspace_id,notebook_id,created_by,type,title,status) VALUES (?,?,?,?,'RAW_TEXT','Evidence','READY')",
                source,
                workspace,
                notebook,
                user);
        jdbc.update(
                "INSERT INTO source_versions(id,source_id,version_no) VALUES (?,?,1)",
                version,
                source);
        jdbc.update("UPDATE sources SET current_version_id=? WHERE id=?", version, source);
        jdbc.update(
                "INSERT INTO document_chunks(id,workspace_id,notebook_id,source_version_id,chunk_key,ordinal,text) VALUES (?,?,?,?,'chunk',0,'Atomicity ensures complete transaction commitment.')",
                chunk,
                workspace,
                notebook,
                version);
        jdbc.update(
                "INSERT INTO concepts(id,workspace_id,notebook_id,name,normalized_name) VALUES (?,?,?,'Atomicity','atomicity')",
                concept,
                workspace,
                notebook);
        jdbc.update(
                "INSERT INTO chunk_concepts(chunk_id,concept_id,confidence) VALUES (?,?,1)",
                chunk,
                concept);
        return new Fixture(user, token, workspace, notebook, source, chunk, concept);
    }

    Map<String, Object> call(
            String method, String path, String token, String key, Object body, int status)
            throws Exception {
        var request =
                switch (method) {
                    case "PATCH" -> patch("/api/v1" + path);
                    case "GET" -> get("/api/v1" + path);
                    default -> post("/api/v1" + path);
                };
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        String result =
                mvc.perform(request)
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return result.isBlank() ? Map.of() : Json.object(result);
    }

    UUID generated(Fixture f, String type) throws Exception {
        String kind = type.equals("QUIZ") ? "quiz" : "flashcards";
        var job =
                call(
                        "POST",
                        "/notebooks/" + f.notebook() + "/artifacts/" + kind,
                        f.token(),
                        UUID.randomUUID().toString(),
                        Map.of("count", 1),
                        202);
        UUID id = UUID.fromString(job.get("id").toString());
        Map<String, Object> artifact =
                type.equals("QUIZ")
                        ? Map.of(
                                "title",
                                "Quiz",
                                "questions",
                                List.of(
                                        Map.of(
                                                "type",
                                                "SHORT_ANSWER",
                                                "prompt",
                                                "Which property?",
                                                "answer",
                                                Map.of("correctAnswer", "Atomicity"),
                                                "explanation",
                                                "Atomicity commits all or nothing.",
                                                "sourceRefs",
                                                List.of(
                                                        Map.of(
                                                                "chunkId",
                                                                f.chunk().toString(),
                                                                "sourceId",
                                                                f.source().toString())))))
                        : Map.of(
                                "title",
                                "Deck",
                                "cards",
                                List.of(
                                        Map.of(
                                                "front",
                                                "Which property?",
                                                "back",
                                                "Atomicity",
                                                "sourceChunkId",
                                                f.chunk().toString())));
        var event =
                Map.<String, Object>of(
                        "eventId",
                        UUID.randomUUID().toString(),
                        "eventVersion",
                        1,
                        "workspaceId",
                        f.workspace().toString(),
                        "eventType",
                        kind + ".generated.v1",
                        "traceId",
                        id.toString(),
                        "payload",
                        Map.of(
                                "artifactJobId",
                                id.toString(),
                                "notebookId",
                                f.notebook().toString(),
                                "userId",
                                f.user().toString(),
                                "artifactType",
                                type,
                                "artifact",
                                artifact));
        studio.acceptEvent(event);
        studio.acceptEvent(event);
        var completed = call("GET", "/jobs/" + id, f.token(), null, null, 200);
        assertThat(Json.write(completed)).doesNotContain("correctAnswer");
        return UUID.fromString(completed.get("resultRef").toString());
    }

    @Test
    void quizCompletionIsAtomicIdempotentAndPrivate() throws Exception {
        var f = fixture();
        UUID quiz = generated(f, "QUIZ");
        var publicQuiz = call("GET", "/quizzes/" + quiz, f.token(), null, null, 200);
        assertThat(Json.write(publicQuiz)).doesNotContain("Atomicity", "correctAnswer");
        UUID question =
                jdbc.queryForObject(
                        "SELECT id FROM quiz_questions WHERE quiz_id=?", UUID.class, quiz);
        UUID attempt =
                UUID.fromString(
                        call(
                                        "POST",
                                        "/quizzes/" + quiz + "/attempts",
                                        f.token(),
                                        null,
                                        Map.of(),
                                        201)
                                .get("id")
                                .toString());
        call(
                "POST",
                "/attempts/" + attempt + "/answers",
                f.token(),
                null,
                Map.of("questionId", question, "answer", "atomicity"),
                200);
        var result =
                call("POST", "/attempts/" + attempt + "/complete", f.token(), null, Map.of(), 200);
        assertThat(result.get("score").toString()).isEqualTo("1.0");
        call("POST", "/attempts/" + attempt + "/complete", f.token(), null, Map.of(), 200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM mastery_evidence WHERE user_id=?",
                                Long.class,
                                f.user()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT evidence_count FROM user_concept_mastery WHERE user_id=? AND concept_id=?",
                                Integer.class,
                                f.user(),
                                f.concept()))
                .isEqualTo(1);
        var outsider = fixture();
        call("GET", "/attempts/" + attempt, outsider.token(), null, null, 404);
        call(
                "POST",
                "/attempts/" + attempt + "/answers",
                f.token(),
                null,
                Map.of("questionId", question, "answer", "different"),
                409);
    }

    @Test
    void reviewCannotDuplicateOrUseWithdrawnMaterial() throws Exception {
        var f = fixture();
        UUID deck = generated(f, "FLASHCARDS");
        UUID card =
                jdbc.queryForObject("SELECT id FROM flashcards WHERE deck_id=?", UUID.class, deck);
        String key = UUID.randomUUID().toString();
        var first =
                call(
                        "POST",
                        "/flashcards/" + card + "/reviews",
                        f.token(),
                        key,
                        Map.of("grade", 3),
                        200);
        assertThat(
                        call(
                                "POST",
                                "/flashcards/" + card + "/reviews",
                                f.token(),
                                key,
                                Map.of("grade", 3),
                                200))
                .isEqualTo(first);
        call(
                "POST",
                "/flashcards/" + card + "/reviews",
                f.token(),
                UUID.randomUUID().toString(),
                Map.of("grade", 4),
                409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flashcard_reviews WHERE card_id=?",
                                Long.class,
                                card))
                .isEqualTo(1);
        invalidation.invalidate(f.workspace(), f.notebook(), f.source());
        call(
                "POST",
                "/flashcards/" + card + "/reviews",
                f.token(),
                UUID.randomUUID().toString(),
                Map.of("grade", 1),
                404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM flashcard_decks WHERE id=?",
                                String.class,
                                deck))
                .isEqualTo("STALE");
    }

    @Test
    void quotaReservationsAreDurableAndCannotBeRacedByRetries() throws Exception {
        var f = fixture();
        String key = UUID.randomUUID().toString();
        usage.reserve(f.user(), f.workspace(), "LANGUAGE", key);
        usage.reserve(f.user(), f.workspace(), "LANGUAGE", key);
        usage.complete(
                f.user(),
                "LANGUAGE",
                key,
                Map.of("model", "test", "inputTokens", 12, "outputTokens", 4),
                key);
        usage.complete(
                f.user(),
                "LANGUAGE",
                key,
                Map.of("model", "test", "inputTokens", 12, "outputTokens", 4),
                key);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM ai_runs WHERE user_id=?",
                                Long.class,
                                f.user()))
                .isEqualTo(1);
        for (int i = 1; i < 10; i++)
            usage.reserve(f.user(), f.workspace(), "LANGUAGE", "request-" + i);
        assertThatThrownBy(() -> usage.reserve(f.user(), f.workspace(), "ARTIFACT", "overflow"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many");
    }
}
