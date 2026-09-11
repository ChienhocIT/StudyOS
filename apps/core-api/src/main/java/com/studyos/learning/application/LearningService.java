package com.studyos.learning.application;

import com.studyos.learning.application.port.LearningStore;
import com.studyos.learning.domain.MasteryPolicy;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.shared.outbox.Outbox;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import com.studyos.workspace.application.WorkspaceAuthorization;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService implements LearningEvidenceGateway, ConceptAcceptance {
    private final LearningStore store;
    private final NotebookAccess notebooks;
    private final WorkspaceAuthorization workspaces;
    private final Outbox outbox;

    public LearningService(
            LearningStore store,
            NotebookAccess notebooks,
            WorkspaceAuthorization workspaces,
            Outbox outbox) {
        this.store = store;
        this.notebooks = notebooks;
        this.workspaces = workspaces;
        this.outbox = outbox;
    }

    @Transactional
    public void record(
            UUID userId,
            UUID notebookId,
            UUID conceptId,
            String type,
            BigDecimal score,
            BigDecimal weight,
            String evidenceKey,
            UUID activityId) {
        var scope = notebooks.requireWrite(userId, notebookId);
        if (!Set.of("QUIZ", "FLASHCARD_RECALL").contains(type)
                || score.signum() < 0
                || score.compareTo(BigDecimal.ONE) > 0
                || weight.signum() <= 0
                || weight.compareTo(BigDecimal.ONE) > 0
                || evidenceKey == null
                || evidenceKey.length() > 160) {
            throw ApiException.badRequest(
                    "EVIDENCE_INVALID", "Evidence is outside the accepted learning policy.");
        }
        if (!store.conceptInNotebook(conceptId, notebookId))
            throw ApiException.badRequest(
                    "CONCEPT_SCOPE_INVALID", "Concept is outside this notebook.");
        var totals =
                store.appendEvidence(
                        userId, conceptId, type, score, weight, evidenceKey, activityId);
        if (totals.isEmpty()) return;
        var projection =
                MasteryPolicy.project(
                        new BigDecimal(totals.get("weightedScores").toString()),
                        new BigDecimal(totals.get("totalWeight").toString()));
        store.updateProjection(userId, conceptId, projection.score(), projection.confidence());
        outbox.publish(
                scope.workspaceId(),
                "CONCEPT",
                conceptId,
                "mastery.updated.v1",
                Map.of(
                        "userId",
                        userId,
                        "conceptId",
                        conceptId,
                        "masteryScore",
                        projection.score(),
                        "confidence",
                        projection.confidence(),
                        "policyVersion",
                        MasteryPolicy.VERSION));
    }

    @Transactional
    public void accept(
            UUID workspaceId,
            UUID notebookId,
            UUID sourceId,
            List<Map<String, Object>> candidates) {
        if (candidates.size() > 100)
            throw ApiException.badRequest("CONCEPT_LIMIT_EXCEEDED", "Too many generated concepts.");
        for (var candidate : candidates) {
            String name = Objects.toString(candidate.get("name"), "").strip();
            if (name.isEmpty() || name.length() > 200) continue;
            String normalized =
                    Normalizer.normalize(name, Normalizer.Form.NFKC)
                            .toLowerCase(Locale.ROOT)
                            .replaceAll("\\s+", " ");
            BigDecimal confidence =
                    new BigDecimal(Objects.toString(candidate.get("confidence"), "0.5"));
            if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) continue;
            Object rawChunks = candidate.get("chunkIds");
            if (!(rawChunks instanceof List<?> values)) continue;
            List<UUID> chunks =
                    values.stream().map(v -> UUID.fromString(v.toString())).distinct().toList();
            store.acceptConcept(
                    workspaceId,
                    notebookId,
                    sourceId,
                    name,
                    normalized,
                    Objects.toString(candidate.get("description"), ""),
                    confidence,
                    chunks);
        }
    }

    public List<Map<String, Object>> concepts(UUID user, UUID notebook) {
        notebooks.requireRead(user, notebook);
        return store.concepts(notebook);
    }

    public List<Map<String, Object>> mastery(UUID user, UUID notebook) {
        notebooks.requireRead(user, notebook);
        return store.mastery(user, notebook);
    }

    public List<Map<String, Object>> goals(UUID user) {
        return store.goals(user);
    }

    @Transactional
    public Map<String, Object> createGoal(
            UUID user,
            UUID workspace,
            String title,
            String description,
            String date,
            Integer minutes) {
        workspaces.requireMember(user, workspace);
        validateGoal(Map.of("title", title));
        if (minutes != null && (minutes < 15 || minutes > 10080))
            throw ApiException.badRequest(
                    "VALIDATION_FAILED", "Weekly minutes must be between 15 and 10080.");
        if (date != null) java.time.LocalDate.parse(date);
        return store.createGoal(user, workspace, title.strip(), description, date, minutes);
    }

    @Transactional
    public Map<String, Object> updateGoal(UUID user, UUID goal, Map<String, Object> changes) {
        var existing =
                store.goal(user, goal)
                        .orElseThrow(
                                () -> ApiException.notFound("GOAL_NOT_FOUND", "Goal not found."));
        workspaces.requireMember(user, Rows.uuid(existing, "workspaceId"));
        validateGoal(changes);
        return store.updateGoal(user, goal, changes);
    }

    @Transactional
    public List<Map<String, Object>> recommendations(UUID user) {
        return store.recommendations(user);
    }

    private void validateGoal(Map<String, Object> changes) {
        if (changes.containsKey("title")
                && (!(changes.get("title") instanceof String s) || s.isBlank() || s.length() > 240))
            throw ApiException.badRequest(
                    "VALIDATION_FAILED", "Goal title must contain 1 to 240 characters.");
        if (changes.containsKey("status")
                && !Set.of("ACTIVE", "PAUSED", "COMPLETED", "CANCELLED")
                        .contains(changes.get("status")))
            throw ApiException.badRequest("VALIDATION_FAILED", "Invalid goal status.");
        if (changes.get("weeklyMinutes") != null
                && (!(changes.get("weeklyMinutes") instanceof Number n)
                        || n.intValue() < 15
                        || n.intValue() > 10080))
            throw ApiException.badRequest("VALIDATION_FAILED", "Invalid weekly minutes.");
        if (changes.get("targetDate") != null)
            java.time.LocalDate.parse(changes.get("targetDate").toString());
    }
}
