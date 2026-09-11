package com.studyos.conversation.application.port;
import com.studyos.conversation.application.ConversationService.Turn;import java.util.Map;import java.util.function.Consumer;
public interface ChatStream {void stream(Turn turn,Consumer<Map<String,Object>> events) throws Exception;}

