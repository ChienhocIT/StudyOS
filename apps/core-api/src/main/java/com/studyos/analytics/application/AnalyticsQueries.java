package com.studyos.analytics.application;

import java.util.*;

public interface AnalyticsQueries {
    Map<String,Object> overview(UUID user,UUID notebook);
}
