package com.studyos.note.application.port;

import java.util.*;

public interface NoteRepository {
    Optional<Map<String, Object>> get(UUID id);

    List<Map<String, Object>> list(UUID user, UUID notebook);

    UUID create(UUID user, UUID workspace, UUID notebook, String title, String content);

    void update(UUID id, String title, String content);

    void delete(UUID id);

    List<UUID> citations(UUID note);

    boolean citationAllowed(UUID citation, UUID user, UUID notebook);

    void link(UUID note, List<UUID> citations);
}
