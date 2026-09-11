package com.studyos.studio.application.port;

import java.math.BigDecimal;
import java.util.*;

public interface StudioStore {
    Optional<Map<String,Object>> jobByKey(UUID user,String key);
    Map<String,Object> createJob(UUID user,UUID notebook,String type,String key,String fingerprint,Object scope,Object options);
    Optional<Map<String,Object>> job(UUID user,UUID id,boolean lock);
    Optional<Map<String,Object>> jobForEvent(UUID workspace,UUID id);
    void completeJob(UUID id,UUID result,Object resultJson);
    void failJob(UUID id,String code);
    boolean claimEvent(UUID event);
    boolean validChunk(UUID workspace,UUID notebook,UUID chunk,List<UUID> sourceIds);
    List<UUID> chunkConcepts(UUID notebook,List<UUID> chunks);
    UUID saveQuiz(UUID user,UUID notebook,UUID job,String title,List<Map<String,Object>> questions);
    UUID saveDeck(UUID user,UUID notebook,UUID job,String title,List<Map<String,Object>> cards);
    Optional<Map<String,Object>> quiz(UUID user,UUID id);
    List<Map<String,Object>> questions(UUID quiz);
    Map<String,Object> startAttempt(UUID user,UUID quiz);
    Optional<Map<String,Object>> attempt(UUID user,UUID attempt,boolean lock);
    void saveAnswer(UUID attempt,UUID question,Object answer);
    List<Map<String,Object>> answers(UUID attempt);
    void scoreAnswer(UUID attempt,UUID question,boolean correct);
    void completeAttempt(UUID attempt,BigDecimal score);
}
