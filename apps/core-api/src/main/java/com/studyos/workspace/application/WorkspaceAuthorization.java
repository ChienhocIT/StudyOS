package com.studyos.workspace.application;
import java.util.UUID;
public interface WorkspaceAuthorization {
    void requireMember(UUID userId,UUID workspaceId);
    void requireRole(UUID userId,UUID workspaceId,String... roles);
}

