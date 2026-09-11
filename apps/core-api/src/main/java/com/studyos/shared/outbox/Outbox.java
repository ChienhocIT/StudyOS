package com.studyos.shared.outbox;

import java.util.*;

public interface Outbox {
    UUID publish(
            UUID workspaceId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Map<String, Object> payload);
}
