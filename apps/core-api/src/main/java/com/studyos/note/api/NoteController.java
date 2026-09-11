package com.studyos.note.api;
import com.studyos.note.application.NoteService;import com.studyos.shared.security.Actor;
import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;
@RestController @RequestMapping("/api/v1")
public class NoteController{
    private final NoteService service;public NoteController(NoteService service){this.service=service;}
    public record Create(@NotBlank @Size(max=240) String title,@NotNull @Size(max=500000) String content,@Size(max=100) List<@NotNull UUID> citationIds){}
    public record Patch(@Pattern(regexp=".*\\S.*",flags=Pattern.Flag.DOTALL) @Size(max=240) String title,@Size(max=500000) String content,@Size(max=100) List<@NotNull UUID> citationIds){}
    @GetMapping("/notebooks/{notebookId}/notes") public List<Map<String,Object>> list(@PathVariable UUID notebookId){return service.list(Actor.currentUserId(),notebookId);}
    @PostMapping("/notebooks/{notebookId}/notes") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@PathVariable UUID notebookId,@Valid @RequestBody Create r){return service.create(Actor.currentUserId(),notebookId,r.title(),r.content(),r.citationIds());}
    @GetMapping("/notes/{id}") public Map<String,Object> get(@PathVariable UUID id){return service.get(Actor.currentUserId(),id);}
    @PatchMapping("/notes/{id}") public Map<String,Object> update(@PathVariable UUID id,@Valid @RequestBody Patch r){return service.update(Actor.currentUserId(),id,r.title(),r.content(),r.citationIds());}
    @DeleteMapping("/notes/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id){service.delete(Actor.currentUserId(),id);}
}

