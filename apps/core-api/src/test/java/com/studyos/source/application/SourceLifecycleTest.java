package com.studyos.source.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyos.notebook.application.NotebookAccess;
import com.studyos.shared.outbox.Outbox;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.port.ObjectStorage;
import com.studyos.source.application.port.SourceRepository;
import com.studyos.studio.application.SourceArtifactInvalidation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceLifecycleTest {
    private final SourceRepository repo = mock(SourceRepository.class);
    private final NotebookAccess notebooks = mock(NotebookAccess.class);
    private final Outbox outbox = mock(Outbox.class);
    private final SourceArtifactInvalidation artifacts = mock(SourceArtifactInvalidation.class);
    private final SourceService service =
            new SourceService(repo, notebooks, mock(ObjectStorage.class), outbox, artifacts);
    private final UUID user = UUID.randomUUID();
    private final UUID notebook = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();

    private void sourceWithStatus(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", source);
        row.put("workspaceId", workspace);
        row.put("notebookId", notebook);
        row.put("currentVersionId", version);
        row.put("status", status);
        row.put("objectKey", null);
        when(repo.get(source, true)).thenReturn(Optional.of(row));
    }

    @Test
    void sourceWithoutOriginalStillQueuesDerivedCleanup() {
        sourceWithStatus("READY");
        service.delete(user, source);
        verify(artifacts).invalidate(workspace, notebook, source);
        verify(repo).status(source, "DELETING", null, null, false);
        verify(outbox)
                .publish(
                        eq(workspace),
                        eq("Source"),
                        eq(source),
                        eq("source.delete.requested.v1"),
                        argThat(
                                payload ->
                                        source.equals(payload.get("sourceId"))
                                                && version.equals(payload.get("sourceVersionId"))
                                                && notebook.equals(payload.get("notebookId"))
                                                && payload.containsKey("objectKey")
                                                && payload.get("objectKey") == null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETING", "DELETED"})
    void repeatedDeleteDoesNotEnqueueAnotherCleanup(String status) {
        sourceWithStatus(status);
        service.delete(user, source);
        verify(notebooks).requireRead(user, notebook);
        verify(notebooks, never()).requireWrite(any(), any());
        verify(repo, never()).status(any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(outbox, artifacts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "DELETING", "DELETED"})
    void deletedStateNeverBypassesNotebookAuthorization(String status) {
        sourceWithStatus(status);
        when(notebooks.requireRead(user, notebook))
                .thenThrow(ApiException.notFound("NOTEBOOK_NOT_FOUND", "Notebook not found."));
        assertThrows(ApiException.class, () -> service.delete(user, source));
        verifyNoInteractions(outbox, artifacts);
        verify(repo, never()).status(any(), any(), any(), any(), anyBoolean());
    }
}
