package com.studyos.source.infrastructure;

import com.studyos.shared.persistence.Json;
import com.studyos.source.application.SourceEventService;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "studyos.messaging-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SourceEventConsumer {
    private final SourceEventService service;

    public SourceEventConsumer(SourceEventService service) {
        this.service = service;
    }

    @RabbitListener(queues = "q.core.source-state.v1")
    public void receive(Message message) {
        service.accept(Json.object(new String(message.getBody(), StandardCharsets.UTF_8)));
    }
}
