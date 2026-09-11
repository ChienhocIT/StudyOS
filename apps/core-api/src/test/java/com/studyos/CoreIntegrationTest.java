package com.studyos;
import com.studyos.shared.persistence.Json;import com.studyos.source.application.port.ObjectStorage;import com.studyos.source.application.SourceEventService;import com.studyos.conversation.application.ConversationService;import com.studyos.conversation.application.port.*;import com.studyos.shared.security.JwtTokens;
import org.junit.jupiter.api.*;import static org.assertj.core.api.Assertions.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import static org.mockito.Mockito.*;
import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;import org.springframework.test.context.DynamicPropertyRegistry;import org.springframework.test.context.DynamicPropertySource;import org.springframework.test.context.bean.override.mockito.MockitoBean;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.test.web.servlet.MockMvc;import org.springframework.jdbc.core.simple.JdbcClient;import org.springframework.data.redis.listener.RedisMessageListenerContainer;import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;import org.testcontainers.junit.jupiter.*;import org.testcontainers.utility.DockerImageName;
import java.util.*;
@SpringBootTest(properties={"spring.config.import=classpath:application.yml","studyos.jwt-secret=test-signing-secret-that-has-at-least-32-bytes","studyos.internal-jwt-secret=test-internal-secret-that-has-at-least-32-bytes","studyos.messaging-enabled=false","studyos.storage.access-key=test","studyos.storage.secret-key=test","spring.data.redis.repositories.enabled=false"})
@AutoConfigureMockMvc @Testcontainers
class CoreIntegrationTest{
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);}
    @Autowired MockMvc mvc;@Autowired JdbcClient db;@Autowired SourceEventService sourceEvents;@Autowired ConversationService conversations;
    @MockitoBean ObjectStorage storage;@MockitoBean ChatStream chat;@MockitoBean StreamEvents streams;@MockitoBean RedisMessageListenerContainer listener;
    record Account(UUID id,String access,String refresh,UUID workspace){}
    @SuppressWarnings("unchecked") Account account()throws Exception{
        var response=postJson("/api/v1/auth/register",null,Map.of("email",UUID.randomUUID()+"@example.com","password","Testing123!","displayName","Learner"),201);var user=(Map<String,Object>)response.get("user");var tokens=(Map<String,Object>)response.get("tokens");String access=tokens.get("accessToken").toString();
        var workspaces=(List<Map<String,Object>>)Json.read(mvc.perform(get("/api/v1/workspaces").header("Authorization","Bearer "+access)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());return new Account(UUID.fromString(user.get("id").toString()),access,tokens.get("refreshToken").toString(),UUID.fromString(workspaces.getFirst().get("id").toString()));
    }
    UUID notebook(Account a)throws Exception{return UUID.fromString(postJson("/api/v1/workspaces/"+a.workspace()+"/notebooks",a.access(),Map.of("title","Database systems"),201).get("id").toString());}
    Map<String,Object> postJson(String path,String access,Object body,int status)throws Exception{var request=post(path).contentType(MediaType.APPLICATION_JSON).content(Json.write(body));if(access!=null)request.header("Authorization","Bearer "+access);var response=mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();return response.isBlank()?Map.of():Json.object(response);}
    @Test void identityRotationRejectsReplayAndRevokesReplacementFamily()throws Exception{
        Account a=account();var rotated=postJson("/api/v1/auth/refresh",null,Map.of("refreshToken",a.refresh()),200);String replacement=rotated.get("refreshToken").toString();assertThat(replacement).isNotEqualTo(a.refresh());
        postJson("/api/v1/auth/refresh",null,Map.of("refreshToken",a.refresh()),401);postJson("/api/v1/auth/refresh",null,Map.of("refreshToken",replacement),401);
        Account b=account();postJson("/api/v1/auth/logout",null,Map.of("refreshToken",b.refresh()),204);postJson("/api/v1/auth/refresh",null,Map.of("refreshToken",b.refresh()),401);
        assertThat(db.sql("select token_hash from refresh_tokens where user_id=:id").param("id",a.id()).query(String.class).list()).allMatch(v->v.matches("[a-f0-9]{64}"));
    }
    @Test void tenantIsolationCoversNotebooksNotesAndConversations()throws Exception{
        Account owner=account(),outsider=account();UUID notebook=notebook(owner);
        mvc.perform(get("/api/v1/notebooks/"+notebook).header("Authorization","Bearer "+outsider.access())).andExpect(status().isNotFound());
        postJson("/api/v1/notebooks/"+notebook+"/notes",outsider.access(),Map.of("title","Intrusion","content","No"),404);
        UUID conversation=UUID.fromString(postJson("/api/v1/notebooks/"+notebook+"/conversations",owner.access(),Map.of("mode","ASK"),201).get("id").toString());
        postJson("/api/v1/conversations/"+conversation+"/ws-token",outsider.access(),Map.of(),404);
        mvc.perform(delete("/api/v1/workspaces/"+owner.workspace()).header("Authorization","Bearer "+outsider.access())).andExpect(status().isNotFound());
    }
    @Test void rawSourcesAreIdempotentAndEventsCannotRegressOrCrossScope()throws Exception{
        Account a=account();UUID notebook=notebook(a);String path="/api/v1/notebooks/"+notebook+"/sources/raw";String key=UUID.randomUUID().toString();String body=Json.write(Map.of("title","Source","content","PostgreSQL transactions provide atomicity."));
        String first=mvc.perform(post(path).header("Authorization","Bearer "+a.access()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second=mvc.perform(post(path).header("Authorization","Bearer "+a.access()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();assertThat(Json.object(second).get("id")).isEqualTo(Json.object(first).get("id"));
        UUID id=UUID.fromString(Json.object(first).get("id").toString());UUID version=db.sql("select current_version_id from sources where id=:id").param("id",id).query(UUID.class).single();
        assertThat(db.sql("select count(*) from outbox_events where aggregate_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1L);
        sourceEvents.accept(event(UUID.randomUUID(),a.workspace(),id,version,"source.ready.v1"));sourceEvents.accept(event(UUID.randomUUID(),a.workspace(),id,version,"source.indexed.v1"));assertThat(db.sql("select status::text from sources where id=:id").param("id",id).query(String.class).single()).isEqualTo("READY");
        sourceEvents.accept(event(UUID.randomUUID(),UUID.randomUUID(),id,version,"source.processing.failed.v1"));assertThat(db.sql("select status::text from sources where id=:id").param("id",id).query(String.class).single()).isEqualTo("READY");
    }
    @Test void chatRequestDedupCancellationAndCitationScopeAreDurable()throws Exception{
        Account a=account();UUID notebook=notebook(a);UUID conversation=UUID.fromString(postJson("/api/v1/notebooks/"+notebook+"/conversations",a.access(),Map.of(),201).get("id").toString());UUID request=UUID.randomUUID();
        var turn=conversations.begin(a.id(),conversation,request,"Explain transactions",null,List.of());assertThat(turn.duplicate()).isFalse();assertThat(conversations.begin(a.id(),conversation,request,"Explain transactions",null,List.of()).duplicate()).isTrue();
        assertThat(conversations.messages(a.id(),conversation,null)).hasSize(2);assertThat(conversations.cancel(a.id(),conversation,request)).isTrue();assertThat(conversations.cancel(a.id(),conversation,request)).isFalse();
        var completed=conversations.complete(turn,Map.of("content","Late answer","groundingStatus","SUPPORTED","citations",List.of()));assertThat(completed.get("status")).isEqualTo("CANCELLED");assertThat(conversations.messages(a.id(),conversation,null).get(1).get("content")).isEqualTo("");
        var next=conversations.begin(a.id(),conversation,UUID.randomUUID(),"Question",null,List.of());assertThatThrownBy(()->conversations.complete(next,Map.of("content","Forged answer","groundingStatus","SUPPORTED","citations",List.of(Map.of("key","C1","chunkId",UUID.randomUUID().toString()))))).isInstanceOf(IllegalArgumentException.class);conversations.failed(next,"");
    }
    static Map<String,Object> event(UUID event,UUID workspace,UUID source,UUID version,String type){return Map.of("eventId",event.toString(),"eventVersion",1,"eventType",type,"workspaceId",workspace.toString(),"payload",Map.of("sourceId",source.toString(),"sourceVersionId",version.toString()));}
}

