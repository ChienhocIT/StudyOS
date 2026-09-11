package com.studyos.language.infrastructure;

import com.studyos.language.application.port.LanguageAnalyzer;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.security.JwtTokens;
import com.studyos.shared.web.ApiException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalLanguageClient implements LanguageAnalyzer {
    private final JwtTokens tokens;
    private final String url;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public InternalLanguageClient(JwtTokens tokens,@Value("${studyos.ai-url}")String url){this.tokens=tokens;this.url=url;}
    public Map<String,Object> analyze(UUID user,UUID workspace,UUID notebook,UUID source,String sentence,String target,String level){
        Map<String,Object> context=Map.of("userId",user.toString(),"workspaceId",workspace.toString(),"notebookId",notebook.toString(),
                "sourceIds",List.of(source.toString()),"traceId",UUID.randomUUID().toString(),"permissions",List.of("language:analyze"));
        var body=Map.of("context",context,"sentence",sentence,"targetLanguage",target,"learnerLevel",level);
        try {
            var request=HttpRequest.newBuilder(URI.create(url+"/internal/v1/language/analyze")).timeout(Duration.ofSeconds(60))
                    .header("Authorization","Bearer "+tokens.internal(user,context)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new ApiException(503,"LANGUAGE_ANALYSIS_UNAVAILABLE","Language analysis is unavailable; configure a compatible AI provider and retry.");
            var result=Json.object(response.body());
            if(!(result.get("translation") instanceof String)||!(result.get("vocabulary") instanceof List<?>)||!(result.get("grammar") instanceof List<?>))
                throw new ApiException(502,"LANGUAGE_OUTPUT_INVALID","AI returned an invalid language analysis.");
            return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiException(503,"AI_PROVIDER_UNAVAILABLE","Language analysis was interrupted.");}
        catch(java.io.IOException e){throw new ApiException(503,"AI_PROVIDER_UNAVAILABLE","Language analysis is temporarily unavailable.");}
    }
}
