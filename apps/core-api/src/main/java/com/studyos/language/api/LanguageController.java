package com.studyos.language.api;

import com.studyos.language.application.LanguageService;
import com.studyos.shared.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class LanguageController {
    private final LanguageService service;
    public LanguageController(LanguageService service){this.service=service;}
    public record Analysis(@NotNull UUID sourceId,UUID transcriptSegmentId,@NotBlank @Size(max=5000) String text,@NotBlank @Size(max=16) String targetLanguage,String learnerLevel){}
    @PostMapping("/language/analyze")public Object analyze(@AuthenticationPrincipal Actor a,@Valid @RequestBody Analysis r){return service.analyze(a.userId(),r.sourceId(),r.transcriptSegmentId(),r.text(),r.targetLanguage(),r.learnerLevel()==null?"B1":r.learnerLevel());}
    @GetMapping("/vocabulary/items")public Object vocabulary(@AuthenticationPrincipal Actor a,@RequestParam(required=false) UUID notebookId){return service.vocabulary(a.userId(),notebookId);}
    public record Vocabulary(@NotNull UUID notebookId,UUID sourceId,UUID transcriptSegmentId,@NotBlank @Size(max=300) String term,@Size(max=5000) String meaning,@Size(max=10000) String context,@Size(max=16)String sourceLanguage,@Size(max=16)String targetLanguage,boolean createFlashcard){}
    @PostMapping("/vocabulary/items") @ResponseStatus(HttpStatus.CREATED)public Object save(@AuthenticationPrincipal Actor a,@Valid @RequestBody Vocabulary r){return service.save(a.userId(),r.notebookId(),r.sourceId(),r.transcriptSegmentId(),r.term(),r.meaning(),r.context(),r.sourceLanguage(),r.targetLanguage(),r.createFlashcard());}
}
