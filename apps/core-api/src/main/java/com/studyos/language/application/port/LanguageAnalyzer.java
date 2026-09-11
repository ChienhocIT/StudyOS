package com.studyos.language.application.port;

import java.util.*;

public interface LanguageAnalyzer {
    Map<String,Object> analyze(UUID user,UUID workspace,UUID notebook,UUID source,String sentence,String targetLanguage,String level);
}
