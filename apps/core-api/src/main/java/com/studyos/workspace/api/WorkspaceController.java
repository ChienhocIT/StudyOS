package com.studyos.workspace.api;
import com.studyos.workspace.application.WorkspaceService;import com.studyos.shared.security.Actor;
import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;
@RestController @RequestMapping("/api/v1/workspaces")
public class WorkspaceController{
    private final WorkspaceService service;public WorkspaceController(WorkspaceService service){this.service=service;}
    public record Create(@NotBlank @Size(max=160) String name){}
    public record Patch(@Pattern(regexp=".*\\S.*",flags=Pattern.Flag.DOTALL) @Size(max=160) String name){}
    @GetMapping public List<Map<String,Object>> list(){return service.list(Actor.currentUserId());}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@Valid @RequestBody Create r){return service.create(Actor.currentUserId(),r.name());}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id){return service.get(Actor.currentUserId(),id);}
    @PatchMapping("/{id}") public Map<String,Object> patch(@PathVariable UUID id,@Valid @RequestBody Patch r){return service.rename(Actor.currentUserId(),id,r.name());}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id){service.delete(Actor.currentUserId(),id);}
    @GetMapping("/{id}/members") public List<Map<String,Object>> members(@PathVariable UUID id){return service.members(Actor.currentUserId(),id);}
}

