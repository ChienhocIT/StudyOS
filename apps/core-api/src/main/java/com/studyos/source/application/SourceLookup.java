package com.studyos.source.application;
import java.util.*;
public interface SourceLookup { List<UUID> requireReady(UUID userId,UUID notebookId,List<UUID> sourceIds); }

