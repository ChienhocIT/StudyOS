package com.studyos.conversation.api;
import com.studyos.conversation.application.ConversationService;import com.studyos.shared.security.Actor;
import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;
@RestController @RequestMapping("/api/v1")
public class ConversationController{
    private final ConversationService service;public ConversationController(ConversationService service){this.service=service;}
    public record Create(@Pattern(regexp="ASK|SOCRATIC|FEYNMAN|EXAM|INTERVIEW|ELI5|EXPERT")String mode,@Size(max=240)String title){}
    public record Feedback(@Min(-1) @Max(1)int rating,@Size(max=80)String reason,@Size(max=4000)String comment){@AssertTrue public boolean isValidRating(){return rating==-1||rating==1;}}
    @PostMapping("/notebooks/{notebookId}/conversations") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@PathVariable UUID notebookId,@Valid @RequestBody Create r){return service.create(Actor.currentUserId(),notebookId,r.mode(),r.title());}
    @GetMapping("/notebooks/{notebookId}/conversations") public List<Map<String,Object>> list(@PathVariable UUID notebookId){return service.list(Actor.currentUserId(),notebookId);}
    @GetMapping("/conversations/{id}") public Map<String,Object> get(@PathVariable UUID id){return service.get(Actor.currentUserId(),id);}
    @GetMapping("/conversations/{id}/messages") public List<Map<String,Object>> messages(@PathVariable UUID id,@RequestParam(required=false)String cursor){return service.messages(Actor.currentUserId(),id,cursor);}
    @PostMapping("/conversations/{id}/ws-token") public Map<String,Object> token(@PathVariable UUID id){return service.wsToken(Actor.currentUserId(),id);}
    @PostMapping("/messages/{id}/feedback") @ResponseStatus(HttpStatus.NO_CONTENT) public void feedback(@PathVariable UUID id,@Valid @RequestBody Feedback r){service.feedback(Actor.currentUserId(),id,r.rating(),r.reason(),r.comment());}
}

