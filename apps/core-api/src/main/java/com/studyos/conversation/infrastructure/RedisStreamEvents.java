package com.studyos.conversation.infrastructure;

import com.studyos.conversation.application.port.StreamEvents;
import com.studyos.shared.persistence.Json;
import java.time.Instant;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamEvents implements StreamEvents {
    public static final String CHANNEL = "studyos.ws.events";
    private final StringRedisTemplate redis;

    public RedisStreamEvents(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static final DefaultRedisScript<String> APPEND =
            new DefaultRedisScript<>(
                    "local e=cjson.decode(ARGV[1]);e.sequence=redis.call('INCR',KEYS[1]);local v=cjson.encode(e);redis.call('RPUSH',KEYS[2],v);redis.call('LTRIM',KEYS[2],-2000,-1);redis.call('EXPIRE',KEYS[2],600);redis.call('PUBLISH',ARGV[2],v);return v",
                    String.class);
    private static final DefaultRedisScript<Long> RATE =
            new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end;return n",
                    Long.class);

    public void publish(
            UUID conversation,
            UUID request,
            UUID message,
            String type,
            Map<String, Object> payload) {
        redis.execute(
                APPEND,
                List.of("ws:" + conversation + ":sequence", "ws:" + conversation + ":events"),
                Json.write(envelope(conversation, request, message, type, payload)),
                CHANNEL);
    }

    public static Map<String, Object> envelope(
            UUID conversation,
            UUID request,
            UUID message,
            String type,
            Map<String, Object> payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("protocolVersion", "1.0");
        frame.put("eventId", UUID.randomUUID());
        frame.put("timestamp", Instant.now());
        frame.put("traceId", request == null ? null : request.toString());
        frame.put("requestId", request);
        frame.put("conversationId", conversation);
        frame.put("messageId", message);
        frame.put("sequence", null);
        frame.put("payload", payload);
        return frame;
    }

    public Replay replay(UUID conversation, long after) {
        String raw = redis.opsForValue().get("ws:" + conversation + ":sequence");
        long last = raw == null ? 0 : Long.parseLong(raw);
        var values = redis.opsForList().range("ws:" + conversation + ":events", 0, -1);
        if (values == null) values = List.of();
        long first =
                values.isEmpty()
                        ? last + 1
                        : ((Number) Json.object(values.getFirst()).get("sequence")).longValue();
        boolean expired = after > last || after < first - 1;
        return new Replay(
                values.stream()
                        .filter(v -> ((Number) Json.object(v).get("sequence")).longValue() > after)
                        .toList(),
                last,
                expired);
    }

    public boolean allow(String key, int limit, int seconds) {
        Long count = redis.execute(RATE, List.of("rate:" + key), Integer.toString(seconds));
        return count != null && count <= limit;
    }
}
