package com.studyos.language.application.port;

import java.util.*;

public interface LanguageStore {
    boolean validSegment(UUID sourceId,UUID segmentId,String text);
    List<Map<String,Object>> vocabulary(UUID user,UUID notebook);
    Map<String,Object> save(UUID user,UUID notebook,UUID source,UUID segment,String term,String normalized,String meaning,String context,String sourceLanguage,String targetLanguage);
}
