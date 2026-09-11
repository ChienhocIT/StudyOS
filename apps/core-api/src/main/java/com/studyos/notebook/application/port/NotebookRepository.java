package com.studyos.notebook.application.port;

import java.util.*;

public interface NotebookRepository {
    Optional<Map<String, Object>> get(UUID id);

    List<Map<String, Object>> list(UUID workspace);

    UUID create(UUID user, UUID workspace, String title, String description, String goal);

    void update(UUID id, String title, String description, String goal, String status);

    void delete(UUID id);
}
