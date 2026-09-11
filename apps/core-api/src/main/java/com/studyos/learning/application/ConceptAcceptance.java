package com.studyos.learning.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ConceptAcceptance {
    void accept(
            UUID workspaceId, UUID notebookId, UUID sourceId, List<Map<String, Object>> candidates);
}
