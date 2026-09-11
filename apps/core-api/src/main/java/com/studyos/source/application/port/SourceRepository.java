package com.studyos.source.application.port;

import java.util.*;

public interface SourceRepository {
    Optional<Map<String, Object>> get(UUID id, boolean lock);

    List<Map<String, Object>> list(UUID notebook);

    Optional<Map<String, Object>> idempotent(UUID user, UUID notebook, String key);

    void lockIdempotency(UUID user, UUID notebook, String key);

    UUID create(
            UUID user,
            UUID workspace,
            UUID notebook,
            String type,
            String title,
            String uri,
            String mime,
            long size,
            String checksum,
            String key,
            String status,
            UUID sourceId,
            UUID versionId,
            String objectKey);

    void queued(UUID sourceId, String checksum, long size, String objectKey);

    Optional<Map<String, Object>> retryResponse(UUID user, UUID source, String key);

    void recordRetry(
            UUID user, UUID source, String key, UUID version, Map<String, Object> response);

    void newAttempt(UUID source, UUID version);

    void status(UUID id, String status, String failure, String detail, boolean retryable);

    List<UUID> ready(UUID notebook);

    List<Map<String, Object>> transcript(UUID version);

    boolean markEvent(UUID event);

    void parsed(UUID version, String normalized, String parser);
}
