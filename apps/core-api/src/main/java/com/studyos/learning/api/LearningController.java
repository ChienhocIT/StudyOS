package com.studyos.learning.api;

import com.studyos.learning.application.LearningService;
import com.studyos.shared.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LearningController {
    private final LearningService service;

    public LearningController(LearningService service) {
        this.service = service;
    }

    @GetMapping("/notebooks/{id}/concepts")
    public Object concepts(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return service.concepts(actor.userId(), id);
    }

    @GetMapping("/notebooks/{id}/mastery")
    public Object mastery(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return service.mastery(actor.userId(), id);
    }

    @GetMapping("/learning/recommendations")
    public Object recommendations(@AuthenticationPrincipal Actor actor) {
        return service.recommendations(actor.userId());
    }

    @GetMapping("/learning/goals")
    public Object goals(@AuthenticationPrincipal Actor actor) {
        return service.goals(actor.userId());
    }

    public record GoalRequest(
            @NotNull UUID workspaceId,
            @NotBlank @Size(max = 240) String title,
            @Size(max = 10000) String description,
            String targetDate,
            @Min(15) @Max(10080) java.math.BigDecimal weeklyMinutes) {}

    @PostMapping("/learning/goals")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object goal(
            @AuthenticationPrincipal Actor actor, @Valid @RequestBody GoalRequest request) {
        return service.createGoal(
                actor.userId(),
                request.workspaceId(),
                request.title(),
                request.description(),
                request.targetDate(),
                request.weeklyMinutes());
    }

    @PatchMapping("/learning/goals/{id}")
    public Object goal(
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> changes) {
        return service.updateGoal(actor.userId(), id, changes);
    }
}
