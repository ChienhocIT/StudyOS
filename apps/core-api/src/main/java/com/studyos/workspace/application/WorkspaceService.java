package com.studyos.workspace.application;

import com.studyos.shared.web.ApiException;
import com.studyos.workspace.application.port.WorkspaceRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService implements WorkspaceAuthorization {
    private final WorkspaceRepository repo;

    public WorkspaceService(WorkspaceRepository repo) {
        this.repo = repo;
    }

    public List<Map<String, Object>> list(UUID user) {
        return repo.list(user);
    }

    public Map<String, Object> get(UUID user, UUID id) {
        return repo.get(user, id)
                .orElseThrow(
                        () -> ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found."));
    }

    public void requireMember(UUID user, UUID id) {
        get(user, id);
    }

    public void requireRole(UUID user, UUID id, String... roles) {
        String role = get(user, id).get("role").toString();
        if (!Arrays.asList(roles).contains(role))
            throw ApiException.forbidden(
                    "INSUFFICIENT_ROLE", "Your workspace role does not allow this action.");
    }

    @Transactional
    public Map<String, Object> create(UUID user, String name) {
        return get(user, repo.create(user, name));
    }

    @Transactional
    public Map<String, Object> rename(UUID user, UUID id, String name) {
        requireRole(user, id, "OWNER", "ADMIN");
        if (name != null) repo.rename(id, name.trim());
        return get(user, id);
    }

    @Transactional
    public void delete(UUID user, UUID id) {
        requireRole(user, id, "OWNER");
        repo.delete(id);
    }

    public List<Map<String, Object>> members(UUID user, UUID id) {
        requireMember(user, id);
        return repo.members(id);
    }
}
