package com.studyos.studio.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.studyos.shared.web.ApiException;
import com.studyos.studio.application.port.StudioStore;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArtifactValidatorTest {
    @Test
    void rejectsMalformedAnswerTypesAndMissingEvidence() {
        var validator = new ArtifactValidator(mock(StudioStore.class));
        UUID workspace = UUID.randomUUID(), notebook = UUID.randomUUID();
        for (var question :
                List.of(
                        Map.of(
                                "type",
                                "UNKNOWN",
                                "prompt",
                                "Question",
                                "answer",
                                Map.of("correctAnswer", "answer"),
                                "sourceRefs",
                                List.of()),
                        Map.of(
                                "type",
                                "TRUE_FALSE",
                                "prompt",
                                "Question",
                                "answer",
                                Map.of("correctAnswer", "true"),
                                "sourceRefs",
                                List.of()),
                        Map.of(
                                "type",
                                "MCQ",
                                "prompt",
                                "Question",
                                "answer",
                                Map.of("correctAnswer", "missing", "options", List.of("A", "B")),
                                "sourceRefs",
                                List.of()),
                        Map.of(
                                "type",
                                "SHORT_ANSWER",
                                "prompt",
                                "Question",
                                "answer",
                                Map.of("correctAnswer", "answer"),
                                "sourceRefs",
                                List.of()))) {
            var artifact =
                    new LinkedHashMap<String, Object>(
                            Map.of("title", "Quiz", "questions", List.of(question)));
            assertThatThrownBy(
                            () ->
                                    validator.validate(
                                            "QUIZ", artifact, workspace, notebook, List.of()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("malformed");
        }
    }

    @Test
    void rejectsInventedStudyGuideCitation() {
        StudioStore store = mock(StudioStore.class);
        when(store.validChunk(any(), any(), any(), anyList())).thenReturn(true);
        var artifact =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "title",
                                "Guide",
                                "content",
                                "Claim [C2]",
                                "sourceRefs",
                                List.of(Map.of("key", "C1", "chunkId", UUID.randomUUID()))));
        assertThatThrownBy(
                        () ->
                                new ArtifactValidator(store)
                                        .validate(
                                                "STUDY_GUIDE",
                                                artifact,
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void rejectsCrossTenantProvenanceBeforePersistence() {
        StudioStore store = mock(StudioStore.class);
        UUID workspace = UUID.randomUUID(), notebook = UUID.randomUUID(), chunk = UUID.randomUUID();
        var artifact =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "title",
                                "Quiz",
                                "questions",
                                List.of(
                                        Map.of(
                                                "type",
                                                "SHORT_ANSWER",
                                                "prompt",
                                                "Question",
                                                "answer",
                                                Map.of("correctAnswer", "answer"),
                                                "sourceRefs",
                                                List.of(Map.of("chunkId", chunk.toString()))))));
        assertThatThrownBy(
                        () ->
                                new ArtifactValidator(store)
                                        .validate(
                                                "QUIZ",
                                                artifact,
                                                workspace,
                                                notebook,
                                                List.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("source evidence");
        verify(store, never()).saveQuiz(any(), any(), any(), any(), any());
    }

    @Test
    void replacesModelConceptIdsWithVerifiedChunkConcepts() {
        StudioStore store = mock(StudioStore.class);
        UUID workspace = UUID.randomUUID(),
                notebook = UUID.randomUUID(),
                chunk = UUID.randomUUID(),
                concept = UUID.randomUUID();
        when(store.validChunk(eq(workspace), eq(notebook), eq(chunk), anyList())).thenReturn(true);
        when(store.chunkConcepts(notebook, List.of(chunk))).thenReturn(List.of(concept));
        var artifact =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "title",
                                "Quiz",
                                "questions",
                                List.of(
                                        Map.of(
                                                "type",
                                                "SHORT_ANSWER",
                                                "prompt",
                                                "Question",
                                                "answer",
                                                Map.of("correctAnswer", "answer"),
                                                "conceptIds",
                                                List.of(UUID.randomUUID()),
                                                "sourceRefs",
                                                List.of(Map.of("chunkId", chunk.toString()))))));
        var result =
                new ArtifactValidator(store)
                        .validate(
                                "QUIZ", artifact, workspace, notebook, List.of(UUID.randomUUID()));
        var question = (Map<?, ?>) ((List<?>) result.get("questions")).getFirst();
        assertThat(question.get("conceptIds")).isEqualTo(List.of(concept));
    }
}
