package com.studyos.source.application.port;

import java.util.List;
import java.util.UUID;

public interface SourceCleanupRepository {
    record Source(UUID id, UUID workspaceId, UUID notebookId, UUID versionId, String objectKey) {}

    /** Lock the container and mark its remaining sources DELETING in the caller's transaction. */
    List<Source> begin(UUID workspaceId, UUID notebookId);
}
