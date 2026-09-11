package com.studyos.source.application;

import com.studyos.shared.outbox.Outbox;
import com.studyos.source.application.port.SourceCleanupRepository;
import com.studyos.studio.application.SourceArtifactInvalidation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SourceScopeCleanupService implements SourceScopeCleanup {
    private final SourceCleanupRepository repository;
    private final SourceArtifactInvalidation artifacts;
    private final Outbox outbox;

    public SourceScopeCleanupService(
            SourceCleanupRepository repository,
            SourceArtifactInvalidation artifacts,
            Outbox outbox) {
        this.repository = repository;
        this.artifacts = artifacts;
        this.outbox = outbox;
    }

    public void notebook(UUID workspaceId, UUID notebookId) {
        schedule(workspaceId, notebookId);
    }

    public void workspace(UUID workspaceId) {
        schedule(workspaceId, null);
    }

    private void schedule(UUID workspaceId, UUID notebookId) {
        for (var source : repository.begin(workspaceId, notebookId)) {
            artifacts.invalidate(source.workspaceId(), source.notebookId(), source.id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sourceId", source.id());
            payload.put("sourceVersionId", source.versionId());
            payload.put("notebookId", source.notebookId());
            payload.put("objectKey", source.objectKey());
            outbox.publish(
                    workspaceId, "Source", source.id(), "source.delete.requested.v1", payload);
        }
    }
}
