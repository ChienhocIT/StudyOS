package com.studyos.shared.outbox;

import com.studyos.shared.persistence.Json;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcOutbox implements Outbox {
    private final JdbcClient db;
    private final Clock clock;

    public JdbcOutbox(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public UUID publish(
            UUID workspaceId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Map<String, Object> payload) {
        UUID event = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event);
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", clock.instant());
        envelope.put("producer", "studyos-core");
        envelope.put("traceId", trace);
        envelope.put("correlationId", payload.getOrDefault("sourceId", aggregateId).toString());
        envelope.put("workspaceId", workspaceId);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("payload", payload);
        db.sql(
                        "insert into outbox_events(id,aggregate_type,aggregate_id,event_type,payload_json,trace_id) values(:id,:type,:aggregate,:event,cast(:payload as jsonb),:trace)")
                .param("id", event)
                .param("type", aggregateType)
                .param("aggregate", aggregateId)
                .param("event", eventType)
                .param("payload", Json.write(envelope))
                .param("trace", trace)
                .update();
        return event;
    }
}
