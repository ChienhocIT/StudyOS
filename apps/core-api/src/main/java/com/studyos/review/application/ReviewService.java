package com.studyos.review.application;

import com.studyos.learning.application.LearningEvidenceGateway;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.review.application.port.ReviewStore;
import com.studyos.review.domain.ConservativeReviewScheduler;
import com.studyos.review.domain.ReviewScheduler;
import com.studyos.shared.outbox.Outbox;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService implements ReviewCardCreation {
    private final ReviewStore store;
    private final NotebookAccess notebooks;
    private final LearningEvidenceGateway evidence;
    private final Outbox outbox;
    private final Clock clock;
    private final ReviewScheduler scheduler = new ConservativeReviewScheduler();

    public ReviewService(
            ReviewStore store,
            NotebookAccess notebooks,
            LearningEvidenceGateway evidence,
            Outbox outbox,
            Clock clock) {
        this.store = store;
        this.notebooks = notebooks;
        this.evidence = evidence;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<Map<String, Object>> queue(UUID user, UUID notebook, int limit) {
        if (notebook != null) notebooks.requireRead(user, notebook);
        return store.queue(user, notebook, Math.clamp(limit, 1, 100));
    }

    @Transactional
    public Map<String, Object> review(UUID user, UUID cardId, int grade, String key) {
        if (key == null || key.isBlank() || key.length() > 120)
            throw ApiException.badRequest(
                    "IDEMPOTENCY_KEY_REQUIRED", "A valid Idempotency-Key is required.");
        if (grade < 1 || grade > 4)
            throw ApiException.badRequest("VALIDATION_FAILED", "Grade must be between 1 and 4.");
        var card =
                store.card(user, cardId, true)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "CARD_NOT_FOUND", "Flashcard not found."));
        UUID notebook = Rows.uuid(card, "notebookId");
        var scope = notebooks.requireWrite(user, notebook);
        var prior = store.priorReview(user, key);
        if (prior.isPresent()) {
            var row = prior.get();
            if (!Rows.uuid(row, "cardId").equals(cardId)
                    || ((Number) row.get("grade")).intValue() != grade)
                throw ApiException.conflict(
                        "IDEMPOTENCY_KEY_REUSED", "This key belongs to another review.");
            return response(row);
        }
        Instant now = clock.instant();
        if (card.get("dueAt") != null && Instant.parse(card.get("dueAt").toString()).isAfter(now))
            throw ApiException.conflict(
                    "CARD_NOT_DUE", "This flashcard is not due for another review.");
        var old = state(card.get("schedulerStateJson"));
        var next = scheduler.schedule(old, grade, now);
        Instant previousReview = store.lastReviewedAt(user, cardId);
        var row = store.saveReview(user, cardId, grade, key, old, next.state(), now, next.dueAt());
        if (card.get("conceptId") != null) {
            long delay =
                    previousReview == null
                            ? 86400
                            : Math.max(0, Duration.between(previousReview, now).getSeconds());
            BigDecimal weight =
                    delay >= 86400
                            ? new BigDecimal("0.35")
                            : delay >= 3600 ? new BigDecimal("0.10") : new BigDecimal("0.02");
            BigDecimal score =
                    switch (grade) {
                        case 1 -> BigDecimal.ZERO;
                        case 2 -> new BigDecimal("0.4");
                        case 3 -> new BigDecimal("0.8");
                        default -> BigDecimal.ONE;
                    };
            evidence.record(
                    user,
                    notebook,
                    Rows.uuid(card, "conceptId"),
                    "FLASHCARD_RECALL",
                    score,
                    weight,
                    "review:" + row.get("id"),
                    Rows.uuid(row, "id"));
        }
        outbox.publish(
                scope.workspaceId(),
                "FLASHCARD",
                cardId,
                "flashcard.reviewed.v1",
                Map.of(
                        "userId",
                        user,
                        "cardId",
                        cardId,
                        "reviewId",
                        row.get("id"),
                        "grade",
                        grade,
                        "nextReviewAt",
                        next.dueAt().toString()));
        return response(row);
    }

    private Map<String, Object> response(Map<String, Object> row) {
        return Map.of(
                "reviewId",
                row.get("id"),
                "cardId",
                row.get("cardId"),
                "nextReviewAt",
                row.get("nextReviewAt"),
                "schedulerState",
                row.get("newStateJson"));
    }

    private ReviewScheduler.State state(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) return ReviewScheduler.State.initial();
        return new ReviewScheduler.State(
                ((Number) m.get("repetitions")).intValue(),
                ((Number) m.get("lapses")).intValue(),
                ((Number) m.get("ease")).doubleValue(),
                ((Number) m.get("intervalSeconds")).longValue());
    }

    @Transactional
    public UUID fromVocabulary(
            UUID user, UUID notebook, UUID vocabulary, String front, String back) {
        notebooks.requireWrite(user, notebook);
        return store.vocabularyCard(user, notebook, vocabulary, front, back);
    }
}
