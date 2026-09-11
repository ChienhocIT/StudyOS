package com.studyos.learning.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Called by validated learner activities inside their existing transaction. */
public interface LearningEvidenceGateway {
    void record(UUID userId, UUID notebookId, UUID conceptId, String type,
                BigDecimal score, BigDecimal weight, String evidenceKey, UUID activityId);
}
