package com.studyos.source.application;

import java.util.UUID;

/** Schedule source erasure before a notebook or workspace is soft-deleted. */
public interface SourceScopeCleanup {
    void notebook(UUID workspaceId, UUID notebookId);

    void workspace(UUID workspaceId);
}
