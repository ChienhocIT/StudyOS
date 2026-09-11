package com.studyos.notebook.api;
import com.studyos.notebook.application.NotebookService;import com.studyos.shared.security.Actor;
import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;
@RestController @RequestMapping("/api/v1")
public class NotebookController{
    private final NotebookService service;public NotebookController(NotebookService service){this.service=service;}
    public record Create(@NotBlank @Size(max=200) String title,@Size(max=20000) String description,@Size(max=20000) String goalText){}
    public record Patch(@Pattern(regexp=".*\\S.*",flags=Pattern.Flag.DOTALL) @Size(max=200) String title,@Size(max=20000) String description,@Size(max=20000) String goalText,@Pattern(regexp="ACTIVE|ARCHIVED") String status){}
    @GetMapping("/workspaces/{workspaceId}/notebooks") public List<Map<String,Object>> list(@PathVariable UUID workspaceId){return service.list(Actor.currentUserId(),workspaceId);}
    @PostMapping("/workspaces/{workspaceId}/notebooks") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@PathVariable UUID workspaceId,@Valid @RequestBody Create r){return service.create(Actor.currentUserId(),workspaceId,r.title(),r.description(),r.goalText());}
    @GetMapping("/notebooks/{id}") public Map<String,Object> get(@PathVariable UUID id){return service.get(Actor.currentUserId(),id);}
    @PatchMapping("/notebooks/{id}") public Map<String,Object> patch(@PathVariable UUID id,@Valid @RequestBody Patch r){return service.update(Actor.currentUserId(),id,r.title(),r.description(),r.goalText(),r.status());}
    @DeleteMapping("/notebooks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id){service.delete(Actor.currentUserId(),id);}
}

