package com.studyos.analytics.infrastructure;

import com.studyos.shared.persistence.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, content-free learning funnel facts. Operational queries use business tables. */
@Configuration
@ConditionalOnProperty(
        name = "studyos.messaging-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LearningEventAudit {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public LearningEventAudit(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Bean
    Declarables learningAuditQueue() {
        var queue =
                QueueBuilder.durable("q.analytics.learning.v1")
                        .deadLetterExchange("studyos.dlx")
                        .build();
        return new Declarables(
                queue,
                new Binding(
                        queue.getName(),
                        Binding.DestinationType.QUEUE,
                        "studyos.learning.events",
                        "#",
                        null));
    }

    @RabbitListener(queues = "q.analytics.learning.v1")
    public void consume(Message message) {
        Map<String, Object> event =
                Json.object(new String(message.getBody(), StandardCharsets.UTF_8));
        UUID eventId = UUID.fromString(event.get("eventId").toString());
        UUID workspace = UUID.fromString(event.get("workspaceId").toString());
        UUID aggregate = UUID.fromString(event.get("aggregateId").toString());
        String type = event.get("eventType").toString();
        if (!Set.of("quiz.attempt.completed.v1", "flashcard.reviewed.v1", "mastery.updated.v1")
                .contains(type)) throw new IllegalArgumentException("Unknown learning event");
        transactions.executeWithoutResult(
                status -> {
                    if (jdbc.update(
                                    "INSERT INTO processed_events(consumer_name,event_id) VALUES ('learning-audit-v1',?) ON CONFLICT DO NOTHING",
                                    eventId)
                            == 0) return;
                    jdbc.update(
                            """
                    INSERT INTO audit_logs(workspace_id,action,entity_type,entity_id,trace_id,metadata_json)
                    VALUES (?,?,?,?,?,?::jsonb)
                    """,
                            workspace,
                            type,
                            event.get("aggregateType").toString(),
                            aggregate,
                            event.get("traceId"),
                            Json.write(Map.of("eventId", eventId)));
                });
    }
}
