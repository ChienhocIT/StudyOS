package com.studyos.learning.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LearningStore {
    boolean conceptInNotebook(UUID conceptId, UUID notebookId);

    Map<String, Object> appendEvidence(
            UUID userId,
            UUID conceptId,
            String type,
            BigDecimal score,
            BigDecimal weight,
            String key,
            UUID activityId);

    void updateProjection(UUID userId, UUID conceptId, BigDecimal score, BigDecimal confidence);

    List<Map<String, Object>> concepts(UUID notebookId);

    List<Map<String, Object>> mastery(UUID userId, UUID notebookId);

    void acceptConcept(
            UUID workspaceId,
            UUID notebookId,
            UUID sourceId,
            String name,
            String normalizedName,
            String description,
            BigDecimal confidence,
            List<UUID> chunks);

    List<Map<String, Object>> goals(UUID userId);

    Optional<Map<String, Object>> goal(UUID userId, UUID goalId);

    Map<String, Object> createGoal(
            UUID userId,
            UUID workspaceId,
            String title,
            String description,
            String targetDate,
            Integer weeklyMinutes);

    Map<String, Object> updateGoal(UUID userId, UUID goalId, Map<String, Object> changes);

    List<Map<String, Object>> recommendations(UUID userId);
}
