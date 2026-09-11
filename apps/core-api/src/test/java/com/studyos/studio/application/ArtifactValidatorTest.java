package com.studyos.studio.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.studyos.studio.application.port.StudioStore;
import com.studyos.shared.web.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArtifactValidatorTest {
    @Test void rejectsCrossTenantProvenanceBeforePersistence(){
        StudioStore store=mock(StudioStore.class);UUID workspace=UUID.randomUUID(),notebook=UUID.randomUUID(),chunk=UUID.randomUUID();
        var artifact=new LinkedHashMap<String,Object>(Map.of("title","Quiz","questions",List.of(Map.of(
                "type","SHORT_ANSWER","prompt","Question","answer",Map.of("correctAnswer","answer"),
                "sourceRefs",List.of(Map.of("chunkId",chunk.toString()))))));
        assertThatThrownBy(()->new ArtifactValidator(store).validate("QUIZ",artifact,workspace,notebook,List.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class).hasMessageContaining("source evidence");
        verify(store,never()).saveQuiz(any(),any(),any(),any(),any());
    }
    @Test void replacesModelConceptIdsWithVerifiedChunkConcepts(){
        StudioStore store=mock(StudioStore.class);UUID workspace=UUID.randomUUID(),notebook=UUID.randomUUID(),chunk=UUID.randomUUID(),concept=UUID.randomUUID();
        when(store.validChunk(eq(workspace),eq(notebook),eq(chunk),anyList())).thenReturn(true);
        when(store.chunkConcepts(notebook,List.of(chunk))).thenReturn(List.of(concept));
        var artifact=new LinkedHashMap<String,Object>(Map.of("title","Quiz","questions",List.of(Map.of(
                "type","SHORT_ANSWER","prompt","Question","answer",Map.of("correctAnswer","answer"),
                "conceptIds",List.of(UUID.randomUUID()),"sourceRefs",List.of(Map.of("chunkId",chunk.toString()))))));
        var result=new ArtifactValidator(store).validate("QUIZ",artifact,workspace,notebook,List.of(UUID.randomUUID()));
        var question=(Map<?,?>)((List<?>)result.get("questions")).getFirst();
        assertThat(question.get("conceptIds")).isEqualTo(List.of(concept));
    }
}
