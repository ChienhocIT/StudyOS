package com.studyos.studio.application;

import java.util.UUID;

/** Withdraw generated learning material when its source is deleted. Caller owns transaction. */
public interface SourceArtifactInvalidation {
    void invalidate(UUID workspaceId, UUID notebookId, UUID sourceId);
}
