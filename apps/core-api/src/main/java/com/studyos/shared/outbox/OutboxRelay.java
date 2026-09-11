package com.studyos.shared.outbox;

import com.studyos.shared.persistence.Rows;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
        name = "studyos.messaging-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcClient db;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;

    public OutboxRelay(JdbcClient db, RabbitTemplate rabbit, TransactionTemplate tx) {
        this.db = db;
        this.rabbit = rabbit;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${studyos.outbox-delay-ms:1000}")
    public void relay() {
        try {
            tx.executeWithoutResult(
                    s -> {
                        var rows =
                                db.sql(
                                                "select id,event_type,payload_json::text as payload from outbox_events where published_at is null and next_publish_at<=now() order by next_publish_at,occurred_at limit 25 for update skip locked")
                                        .query(Rows::map)
                                        .list();
                        for (var row : rows) {
                            String event = row.get("eventType").toString();
                            String exchange = exchange(event);
                            var correlation = new CorrelationData(row.get("id").toString());
                            var props = new MessageProperties();
                            props.setContentType("application/json");
                            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            props.setMessageId(row.get("id").toString());
                            try {
                                rabbit.send(
                                        exchange,
                                        event,
                                        new Message(
                                                row.get("payload")
                                                        .toString()
                                                        .getBytes(StandardCharsets.UTF_8),
                                                props),
                                        correlation);
                                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                                if (!confirm.isAck() || correlation.getReturned() != null) {
                                    throw new IllegalStateException("Publish not routed/confirmed");
                                }
                                db.sql(
                                                "update outbox_events set published_at=now(),publish_attempts=publish_attempts+1 where id=:id")
                                        .param("id", row.get("id"))
                                        .update();
                            } catch (Exception ex) {
                                db.sql(
                                                "update outbox_events set publish_attempts=publish_attempts+1,next_publish_at=now()+make_interval(secs=>least(300,power(2,least(publish_attempts+1,9)))::int) where id=:id")
                                        .param("id", row.get("id"))
                                        .update();
                                log.warn("Outbox publish pending for event {}", row.get("id"));
                                continue;
                            }
                        }
                    });
        } catch (Exception e) {
            log.warn("Outbox relay unavailable: {}", e.getClass().getSimpleName());
        }
    }

    public static String exchange(String event) {
        if (event.startsWith("source.") || event.startsWith("transcript.")) {
            return event.contains(".requested.")
                    ? "studyos.source.commands"
                    : "studyos.source.events";
        }
        if (event.startsWith("quiz.generate.")
                || event.startsWith("flashcards.generate.")
                || event.startsWith("study-guide.generate.")
                || event.startsWith("artifact.")) {
            return event.contains(".requested.")
                    ? "studyos.artifact.commands"
                    : "studyos.artifact.events";
        }
        return "studyos.learning.events";
    }
}
