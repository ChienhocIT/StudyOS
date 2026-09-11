package com.studyos.conversation.api;

import com.studyos.conversation.application.ConversationService;
import com.studyos.shared.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    public record Create(
            @Pattern(regexp = "ASK|SOCRATIC|FEYNMAN|EXAM|INTERVIEW|ELI5|EXPERT") String mode,
            @Size(max = 240) String title) {}

    public record Feedback(
            @Min(-1) @Max(1) int rating,
            @Size(max = 80) String reason,
            @Size(max = 4000) String comment) {
        @AssertTrue
        public boolean isValidRating() {
            return rating == -1 || rating == 1;
        }
    }

    @PostMapping("/notebooks/{notebookId}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable UUID notebookId, @Valid @RequestBody Create r) {
        return service.create(Actor.currentUserId(), notebookId, r.mode(), r.title());
    }

    @GetMapping("/notebooks/{notebookId}/conversations")
    public List<Map<String, Object>> list(@PathVariable UUID notebookId) {
        return service.list(Actor.currentUserId(), notebookId);
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return service.get(Actor.currentUserId(), id);
    }

    @GetMapping("/conversations/{id}/messages")
    public org.springframework.http.ResponseEntity<List<Map<String, Object>>> messages(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "100") int pageSize) {
        var page = service.messagePage(Actor.currentUserId(), id, cursor, before, pageSize);
        var response =
                org.springframework.http.ResponseEntity.ok()
                        .header("X-Has-More", Boolean.toString(page.hasMore()));
        if (page.nextCursor() != null) response.header("X-Next-Cursor", page.nextCursor());
        return response.body(page.items());
    }

    @PostMapping("/conversations/{id}/ws-token")
    public Map<String, Object> token(@PathVariable UUID id) {
        return service.wsToken(Actor.currentUserId(), id);
    }

    @PostMapping("/messages/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@PathVariable UUID id, @Valid @RequestBody Feedback r) {
        service.feedback(Actor.currentUserId(), id, r.rating(), r.reason(), r.comment());
    }
}
