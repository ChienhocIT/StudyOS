package com.studyos.review.application.port;

import java.time.Instant;
import java.util.*;

public interface ReviewStore {
    List<Map<String,Object>> queue(UUID userId,UUID notebookId,int limit);
    Optional<Map<String,Object>> card(UUID userId,UUID cardId,boolean lock);
    Optional<Map<String,Object>> priorReview(UUID userId,String key);
    Instant lastReviewedAt(UUID userId,UUID cardId);
    Map<String,Object> saveReview(UUID userId,UUID cardId,int grade,String key,Object oldState,Object newState,Instant now,Instant dueAt);
    UUID vocabularyCard(UUID userId,UUID notebookId,UUID vocabularyId,String front,String back);
}
