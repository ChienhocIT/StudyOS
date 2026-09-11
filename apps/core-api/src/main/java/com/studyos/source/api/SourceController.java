package com.studyos.source.api;
import com.studyos.source.application.SourceService;import com.studyos.shared.security.Actor;
import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;
@RestController @RequestMapping("/api/v1") @Validated
public class SourceController{
    private final SourceService service;public SourceController(SourceService service){this.service=service;}
    public record Raw(@NotBlank @Size(max=300) String title,@NotBlank @Size(max=500000) String content){}
    public record Url(@NotBlank @Size(max=4000) String url,@Size(max=300) String title){}
    public record Upload(@NotBlank @Size(max=300) String fileName,@NotBlank @Size(max=120) String mimeType,@Min(1) @Max(104857600) long sizeBytes,@Pattern(regexp="[a-f0-9]{64}") String checksumSha256){}
    public record Complete(@NotNull UUID sourceId,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String checksumSha256,@Min(1) @Max(104857600) long sizeBytes){}
    @GetMapping("/notebooks/{notebookId}/sources") public List<Map<String,Object>> list(@PathVariable UUID notebookId){return service.list(Actor.currentUserId(),notebookId);}
    @PostMapping("/notebooks/{notebookId}/sources/raw") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> raw(@PathVariable UUID notebookId,@Valid @RequestBody Raw r,@RequestHeader(value="Idempotency-Key",required=false) @Size(min=1,max=120) String key){return service.raw(Actor.currentUserId(),notebookId,r.title(),r.content(),key);}
    @PostMapping("/notebooks/{notebookId}/sources/url") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> url(@PathVariable UUID notebookId,@Valid @RequestBody Url r,@RequestHeader(value="Idempotency-Key",required=false) @Size(min=1,max=120) String key){return service.url(Actor.currentUserId(),notebookId,r.url(),r.title(),key);}
    @PostMapping("/notebooks/{notebookId}/sources/upload-init") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> upload(@PathVariable UUID notebookId,@Valid @RequestBody Upload r,@RequestHeader(value="Idempotency-Key",required=false) @Size(min=1,max=120) String key){return service.upload(Actor.currentUserId(),notebookId,r.fileName(),r.mimeType(),r.sizeBytes(),r.checksumSha256(),key);}
    @PostMapping("/notebooks/{notebookId}/sources/upload-complete") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> complete(@PathVariable UUID notebookId,@Valid @RequestBody Complete r){return service.complete(Actor.currentUserId(),notebookId,r.sourceId(),r.checksumSha256(),r.sizeBytes());}
    @GetMapping({"/sources/{id}","/sources/{id}/status"}) public Map<String,Object> get(@PathVariable UUID id){return service.get(Actor.currentUserId(),id);}
    @PostMapping("/sources/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> retry(@PathVariable UUID id){return service.retry(Actor.currentUserId(),id);}
    @DeleteMapping("/sources/{id}") @ResponseStatus(HttpStatus.ACCEPTED) public void delete(@PathVariable UUID id){service.delete(Actor.currentUserId(),id);}
    @GetMapping("/sources/{id}/transcript") public List<Map<String,Object>> transcript(@PathVariable UUID id){return service.transcript(Actor.currentUserId(),id);}
}

