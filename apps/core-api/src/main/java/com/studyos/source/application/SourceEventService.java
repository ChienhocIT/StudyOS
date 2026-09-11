package com.studyos.source.application;

import com.studyos.learning.application.ConceptAcceptance;
import com.studyos.shared.persistence.Rows;
import com.studyos.source.application.port.SourceRepository;
import com.studyos.source.domain.SourceState;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceEventService {
    private final SourceRepository repo;
    private final ConceptAcceptance concepts;

    public SourceEventService(SourceRepository repo, ConceptAcceptance concepts) {
        this.repo = repo;
        this.concepts = concepts;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void accept(Map<String, Object> event) {
        if (((Number) event.get("eventVersion")).intValue() != 1)
            throw new IllegalArgumentException("Unsupported source event version");
        UUID eventId = UUID.fromString(event.get("eventId").toString());
        if (!repo.markEvent(eventId)) return;
        String type = event.get("eventType").toString();
        Map<String, Object> p = (Map<String, Object>) event.get("payload");
        UUID id = UUID.fromString(p.get("sourceId").toString()),
                version = UUID.fromString(p.get("sourceVersionId").toString());
        var row = repo.get(id, true);
        if (row.isEmpty()) return;
        var source = row.get();
        if (!Rows.uuid(source, "workspaceId").toString().equals(event.get("workspaceId").toString())
                || !version.equals(Rows.uuid(source, "currentVersionId"))) return;
        SourceState current = SourceState.valueOf(source.get("status").toString());
        if (type.equals("source.deleted.v1")) {
            if (current == SourceState.DELETING) repo.status(id, "DELETED", null, null, false);
            return;
        }
        SourceState next =
                switch (type) {
                    case "source.parsed.v1", "source.transcript.ready.v1" -> SourceState.NORMALIZED;
                    case "source.indexed.v1" -> SourceState.ENRICHING;
                    case "source.ready.v1" -> SourceState.READY;
                    case "source.processing.failed.v1" -> SourceState.FAILED;
                    default -> null;
                };
        if (next == null || !current.canAdvanceTo(next)) return;
        if (type.equals("source.parsed.v1"))
            repo.parsed(
                    version,
                    (String) p.get("normalizedObjectKey"),
                    (String) p.get("parserVersion"));
        if (next == SourceState.READY && p.get("concepts") instanceof List<?> candidates)
            concepts.accept(
                    Rows.uuid(source, "workspaceId"),
                    Rows.uuid(source, "notebookId"),
                    id,
                    (List<Map<String, Object>>) candidates);
        repo.status(
                id,
                next.name(),
                next == SourceState.FAILED
                        ? Objects.toString(p.get("errorCode"), "PROCESSING_FAILED")
                        : null,
                next == SourceState.FAILED
                        ? Objects.toString(p.get("safeDetail"), "Source processing failed.")
                        : null,
                next == SourceState.FAILED && Boolean.TRUE.equals(p.get("retryable")));
    }
}
