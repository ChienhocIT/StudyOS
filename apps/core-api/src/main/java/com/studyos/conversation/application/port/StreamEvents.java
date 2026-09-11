package com.studyos.conversation.application.port;
import java.util.*;
public interface StreamEvents{
    void publish(UUID conversation,UUID request,UUID message,String type,Map<String,Object> payload);
    record Replay(List<String> events,long lastSequence,boolean expired){}
    Replay replay(UUID conversation,long after);
    boolean allow(String key,int limit,int seconds);
}

