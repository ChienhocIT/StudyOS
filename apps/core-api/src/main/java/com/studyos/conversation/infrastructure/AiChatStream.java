package com.studyos.conversation.infrastructure;
import com.studyos.conversation.application.port.ChatStream;import com.studyos.conversation.application.ConversationService.Turn;import com.studyos.shared.persistence.Json;import com.studyos.shared.security.JwtTokens;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;
import java.net.*;import java.net.http.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.time.Duration;import java.util.*;import java.util.function.Consumer;
@Component
public class AiChatStream implements ChatStream{
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();private final JwtTokens jwt;private final String aiUrl;
    public AiChatStream(JwtTokens jwt,@Value("${studyos.ai-url}")String aiUrl){this.jwt=jwt;this.aiUrl=aiUrl;}
    public void stream(Turn t,Consumer<Map<String,Object>> events)throws Exception{
        Map<String,Object> context=new LinkedHashMap<>();context.put("userId",t.userId().toString());context.put("workspaceId",t.workspaceId().toString());context.put("notebookId",t.notebookId().toString());context.put("sourceIds",t.sourceIds().stream().map(UUID::toString).toList());context.put("conversationId",t.conversationId().toString());context.put("traceId",t.traceId());context.put("permissions",List.of("chat:generate"));
        Map<String,Object> body=Map.of("context",context,"requestId",t.requestId(),"messageId",t.messageId(),"content",t.content(),"mode",t.mode(),"history",t.history());
        var request=HttpRequest.newBuilder(URI.create(aiUrl+"/internal/v1/chat/stream")).timeout(Duration.ofSeconds(180)).header("Authorization","Bearer "+jwt.internal(t.userId(),context)).header("Content-Type","application/json").header("Accept","application/x-ndjson").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var input=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))){if(response.statusCode()!=200)throw new IOException("AI returned HTTP "+response.statusCode());String line;while((line=input.readLine())!=null){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(line.length()>1000000)throw new IOException("AI event too large");if(!line.isBlank())events.accept(Json.object(line));}}
    }
}

