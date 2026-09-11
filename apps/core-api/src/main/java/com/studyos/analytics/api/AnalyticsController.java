package com.studyos.analytics.api;

import com.studyos.analytics.application.AnalyticsService;
import com.studyos.shared.security.Actor;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service){this.service=service;}
    @GetMapping("/overview")public Object overview(@AuthenticationPrincipal Actor actor){return service.overview(actor.userId(),null);}
    @GetMapping("/notebooks/{id}")public Object notebook(@AuthenticationPrincipal Actor actor,@PathVariable UUID id){return service.overview(actor.userId(),id);}
}
