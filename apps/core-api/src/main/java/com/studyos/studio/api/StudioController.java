package com.studyos.studio.api;

import com.studyos.shared.security.Actor;
import com.studyos.shared.web.ApiException;
import com.studyos.studio.application.StudioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StudioController {
    private final StudioService service;

    public StudioController(StudioService service) {
        this.service = service;
    }

    public record GenerateRequest(
            List<UUID> sourceIds,
            List<UUID> conceptIds,
            @DecimalMin("0") @DecimalMax("1") BigDecimal difficulty,
            @Min(1) @Max(50) Integer count,
            Boolean focusWeakConcepts) {}

    @PostMapping("/notebooks/{notebookId}/artifacts/{kind}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object generate(
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID notebookId,
            @PathVariable String kind,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GenerateRequest r) {
        String type =
                switch (kind) {
                    case "quiz" -> "QUIZ";
                    case "flashcards" -> "FLASHCARDS";
                    case "study-guide" -> "STUDY_GUIDE";
                    default ->
                            throw ApiException.notFound(
                                    "ARTIFACT_TYPE_UNKNOWN", "Unknown artifact type.");
                };
        Map<String, Object> options = new TreeMap<>();
        options.put("count", r.count() == null ? 5 : r.count());
        if (r.difficulty() != null) options.put("difficulty", r.difficulty());
        if (r.conceptIds() != null && !r.conceptIds().isEmpty())
            throw ApiException.badRequest(
                    "ARTIFACT_OPTION_UNAVAILABLE",
                    "Concept-targeted generation is not enabled yet; select source scope.");
        if (Boolean.TRUE.equals(r.focusWeakConcepts()))
            throw ApiException.badRequest(
                    "ARTIFACT_OPTION_UNAVAILABLE", "Weak-concept targeting is not enabled yet.");
        return service.generate(
                actor.userId(),
                notebookId,
                type,
                key,
                r.sourceIds() == null ? List.of() : r.sourceIds(),
                options);
    }

    @GetMapping("/jobs/{id}")
    public Object job(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return service.job(actor.userId(), id);
    }

    @GetMapping("/quizzes/{id}")
    public Object quiz(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return service.quiz(actor.userId(), id);
    }

    @PostMapping("/quizzes/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public Object start(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return service.start(actor.userId(), id);
    }

    public record AnswerRequest(@NotNull UUID questionId, @NotNull Object answer) {}

    @PostMapping("/attempts/{id}/answers")
    public Object answer(
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody AnswerRequest r) {
        return service.answer(actor.userId(), id, r.questionId(), r.answer());
    }

    @PostMapping("/attempts/{id}/complete")
    public Object complete(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return service.complete(actor.userId(), id);
    }

    @GetMapping("/attempts/{id}")
    public Object result(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return service.result(actor.userId(), id);
    }
}
