package com.studyos.shared.messaging;
import org.springframework.context.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.amqp.core.*;import java.util.ArrayList;import java.util.List;
@Configuration @ConditionalOnProperty(name="studyos.messaging-enabled",havingValue="true",matchIfMissing=true)
public class MessagingConfig {
    @Bean Declarables topology(){
        List<Declarable> elements=new ArrayList<>();
        for(String exchange:List.of("studyos.source.commands","studyos.source.events","studyos.artifact.commands","studyos.artifact.events","studyos.learning.events","studyos.dlx"))elements.add(new TopicExchange(exchange,true,false));
        Queue q=QueueBuilder.durable("q.core.source-state.v1").deadLetterExchange("studyos.dlx").build();elements.add(q);elements.add(new Binding(q.getName(),Binding.DestinationType.QUEUE,"studyos.source.events","source.#",null));
        Queue dlq=QueueBuilder.durable("q.core.dead.v1").build();elements.add(dlq);elements.add(new Binding(dlq.getName(),Binding.DestinationType.QUEUE,"studyos.dlx","#",null));
        return new Declarables(elements);
    }
}

