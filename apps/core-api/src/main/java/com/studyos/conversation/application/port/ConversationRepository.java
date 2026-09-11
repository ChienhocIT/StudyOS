package com.studyos.conversation.application.port;
import java.util.*;
public interface ConversationRepository{
    UUID create(UUID user,UUID workspace,UUID notebook,String mode,String title);
    Optional<Map<String,Object>> get(UUID id,boolean lock);
    List<Map<String,Object>> list(UUID user,UUID notebook);
    List<Map<String,Object>> messages(UUID conversation,long after);
    List<Map<String,Object>> citations(UUID message);
    Optional<Map<String,Object>> generation(UUID conversation,UUID request,boolean lock);
    long activeCount(UUID user);void lockUser(UUID user);
    UUID begin(UUID conversation,UUID request,UUID user,String content,List<UUID> sources,String trace);
    void finish(UUID conversation,UUID request,String content,String status,String grounding);
    Optional<Map<String,Object>> citationChunk(UUID chunk,UUID workspace,UUID notebook,List<UUID> sources);
    UUID citation(UUID message,UUID chunk,String key,int rank);
    Optional<Map<String,Object>> message(UUID message);
    void feedback(UUID user,UUID message,int rating,String reason,String comment);
    void expireStale();
}

