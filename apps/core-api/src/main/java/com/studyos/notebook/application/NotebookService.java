package com.studyos.notebook.application;

import com.studyos.notebook.application.port.NotebookRepository;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.SourceScopeCleanup;
import com.studyos.workspace.application.WorkspaceAuthorization;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotebookService implements NotebookAccess {
    private final NotebookRepository repo;
    private final WorkspaceAuthorization access;
    private final SourceScopeCleanup cleanup;

    public NotebookService(
            NotebookRepository repo, WorkspaceAuthorization access, SourceScopeCleanup cleanup) {
        this.repo = repo;
        this.access = access;
        this.cleanup = cleanup;
    }

    public Map<String, Object> get(UUID user, UUID id) {
        var row =
                repo.get(id)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "NOTEBOOK_NOT_FOUND", "Notebook not found."));
        access.requireMember(user, Rows.uuid(row, "workspaceId"));
        return row;
    }

    public NotebookScope requireRead(UUID user, UUID id) {
        return new NotebookScope(id, Rows.uuid(get(user, id), "workspaceId"));
    }

    public NotebookScope requireWrite(UUID user, UUID id) {
        var row = get(user, id);
        if (!"ACTIVE".equals(row.get("status")))
            throw ApiException.conflict(
                    "NOTEBOOK_ARCHIVED", "Restore the notebook before changing its content.");
        return new NotebookScope(id, Rows.uuid(row, "workspaceId"));
    }

    public List<Map<String, Object>> list(UUID user, UUID workspace) {
        access.requireMember(user, workspace);
        return repo.list(workspace);
    }

    @Transactional
    public Map<String, Object> create(
            UUID user, UUID workspace, String title, String description, String goal) {
        access.requireMember(user, workspace);
        return get(user, repo.create(user, workspace, title, description, goal));
    }

    @Transactional
    public Map<String, Object> update(
            UUID user, UUID id, String title, String description, String goal, String status) {
        requireRead(user, id);
        repo.update(id, title, description, goal, status);
        return get(user, id);
    }

    @Transactional
    public void delete(UUID user, UUID id) {
        var scope = requireRead(user, id);
        access.requireRole(user, scope.workspaceId(), "OWNER", "ADMIN");
        cleanup.notebook(scope.workspaceId(), id);
        repo.delete(id);
    }
}
