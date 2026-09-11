package com.studyos.notebook.application;
import java.util.UUID;
public interface NotebookAccess {
    NotebookScope requireRead(UUID userId,UUID notebookId);
    NotebookScope requireWrite(UUID userId,UUID notebookId);
}

