package com.studyos.studio.infrastructure;

import com.studyos.shared.persistence.Json;
import com.studyos.studio.application.StudioService;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration @ConditionalOnProperty(name="studyos.messaging-enabled",havingValue="true",matchIfMissing=true)
public class ArtifactEvents {
    private final StudioService service;
    public ArtifactEvents(StudioService service){this.service=service;}
    @Bean Declarables artifactStateQueue(){
        var queue=QueueBuilder.durable("q.core.artifact-state.v1").deadLetterExchange("studyos.dlx").build();
        List<Declarable> definitions=new ArrayList<>();definitions.add(queue);
        for(String key:List.of("quiz.generated.v1","flashcards.generated.v1","study-guide.generated.v1","artifact.generation.failed.v1"))
            definitions.add(new Binding(queue.getName(),Binding.DestinationType.QUEUE,"studyos.artifact.events",key,null));
        return new Declarables(definitions);
    }
    @RabbitListener(queues="q.core.artifact-state.v1") public void consume(Message message){service.acceptEvent(Json.object(new String(message.getBody(),StandardCharsets.UTF_8)));}
}
