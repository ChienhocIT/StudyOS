package com.studyos.workspace.application.port;

import java.util.*;

public interface WorkspaceRepository {
    List<Map<String, Object>> list(UUID user);

    Optional<Map<String, Object>> get(UUID user, UUID workspace);

    UUID create(UUID user, String name);

    void rename(UUID id, String name);

    void delete(UUID id);

    List<Map<String, Object>> members(UUID id);
}
