package com.studyos.analytics.application;

import com.studyos.notebook.application.NotebookAccess;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private final AnalyticsQueries queries;private final NotebookAccess notebooks;
    public AnalyticsService(AnalyticsQueries queries,NotebookAccess notebooks){this.queries=queries;this.notebooks=notebooks;}
    public Map<String,Object> overview(UUID user,UUID notebook){if(notebook!=null)notebooks.requireRead(user,notebook);return queries.overview(user,notebook);}
}
