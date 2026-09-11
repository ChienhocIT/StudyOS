package com.studyos.source.infrastructure;
import com.studyos.source.application.SourceEventService;import com.studyos.shared.persistence.Json;
import org.springframework.stereotype.Component;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.amqp.rabbit.annotation.RabbitListener;import org.springframework.amqp.core.Message;import java.nio.charset.StandardCharsets;
@Component @ConditionalOnProperty(name="studyos.messaging-enabled",havingValue="true",matchIfMissing=true)
public class SourceEventConsumer{
    private final SourceEventService service;public SourceEventConsumer(SourceEventService service){this.service=service;}
    @RabbitListener(queues="q.core.source-state.v1") public void receive(Message message){service.accept(Json.object(new String(message.getBody(),StandardCharsets.UTF_8)));}
}

