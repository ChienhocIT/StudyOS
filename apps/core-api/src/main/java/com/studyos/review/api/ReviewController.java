package com.studyos.review.api;

import com.studyos.review.application.ReviewService;
import com.studyos.shared.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/review/queue")
    public Object queue(
            @AuthenticationPrincipal Actor actor,
            @RequestParam(required = false) UUID notebookId,
            @RequestParam(defaultValue = "50") int limit) {
        return service.queue(actor.userId(), notebookId, limit);
    }

    public record GradeRequest(@NotNull @Min(1) @Max(4) Integer grade, String reviewedAt) {}

    @PostMapping("/flashcards/{cardId}/reviews")
    public Object review(
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID cardId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GradeRequest request) {
        return service.review(actor.userId(), cardId, request.grade(), key);
    }
}
