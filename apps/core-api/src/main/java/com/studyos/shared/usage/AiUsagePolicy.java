package com.studyos.shared.usage;

import com.studyos.shared.persistence.Json;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable request budgets shared by all provider-triggering business operations. */
@Service
public class AiUsagePolicy {
    private final JdbcTemplate jdbc;

    public AiUsagePolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void reserve(UUID user, UUID workspace, String feature, String requestKey) {
        if (requestKey == null || requestKey.length() > 160 || feature.length() > 64)
            throw ApiException.badRequest("VALIDATION_FAILED", "Invalid AI request identity.");
        // One user lock serializes quota checks across API nodes and feature types.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, user);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM ai_usage_reservations WHERE user_id=? AND feature=? AND request_key=?)",
                        Boolean.class,
                        user,
                        feature,
                        requestKey))) return;
        String plan =
                jdbc.queryForObject(
                        "SELECT plan::text FROM workspaces WHERE id=? AND status='ACTIVE'",
                        String.class,
                        workspace);
        int daily =
                switch (plan) {
                    case "PRO" -> 500;
                    case "TEAM" -> 1000;
                    default -> 50;
                };
        long used =
                jdbc.queryForObject(
                        "SELECT count(*) FROM ai_usage_reservations WHERE user_id=? AND created_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'",
                        Long.class,
                        user);
        long recent =
                jdbc.queryForObject(
                        "SELECT count(*) FROM ai_usage_reservations WHERE user_id=? AND created_at>now()-interval '1 minute'",
                        Long.class,
                        user);
        if (used >= daily)
            throw new ApiException(
                    429, "QUOTA_EXCEEDED", "Daily AI request allowance has been reached.");
        if (recent >= 10)
            throw new ApiException(
                    429, "RATE_LIMITED", "Too many AI requests. Try again in a minute.");
        jdbc.update(
                "INSERT INTO ai_usage_reservations(user_id,workspace_id,feature,request_key) VALUES (?,?,?,?)",
                user,
                workspace,
                feature,
                requestKey);
    }

    @Transactional
    public void complete(
            UUID user, String feature, String requestKey, Map<String, Object> usage, String trace) {
        var rows =
                jdbc.query(
                        "SELECT * FROM ai_usage_reservations WHERE user_id=? AND feature=? AND request_key=? FOR UPDATE",
                        Rows::map,
                        user,
                        feature,
                        requestKey);
        if (rows.isEmpty()) throw new IllegalStateException("AI usage was not reserved");
        var row = rows.getFirst();
        if (!"RESERVED".equals(row.get("status"))) return;
        Object modelValue = usage.getOrDefault("model", "unknown");
        String model = Objects.toString(modelValue, "unknown");
        if (model.length() > 120) model = model.substring(0, 120);
        Integer input = tokenCount(usage.get("inputTokens")),
                output = tokenCount(usage.get("outputTokens"));
        BigDecimal cost = null;
        if (usage.get("estimatedCostUsd") != null) {
            cost = new BigDecimal(usage.get("estimatedCostUsd").toString());
            if (cost.signum() < 0 || cost.compareTo(new BigDecimal("999999")) > 0)
                throw new IllegalArgumentException("Invalid provider cost");
        }
        Map<String, Object> metadata =
                Map.of(
                        "tokenUsageAvailable",
                        input != null && output != null,
                        "costAvailable",
                        cost != null);
        jdbc.update(
                """
                INSERT INTO ai_runs(user_id,workspace_id,feature,model,input_tokens,output_tokens,estimated_cost_usd,
                  provider_status,trace_id,metadata_json,reservation_id)
                VALUES (?,?,?,?,?,?,?,'COMPLETED',?,?::jsonb,?) ON CONFLICT(reservation_id) DO NOTHING
                """,
                user,
                row.get("workspaceId"),
                feature,
                model,
                input,
                output,
                cost,
                trace == null ? UUID.randomUUID().toString() : trace,
                Json.write(metadata),
                row.get("id"));
        jdbc.update(
                "UPDATE ai_usage_reservations SET status='COMPLETED',finished_at=now() WHERE id=?",
                row.get("id"));
    }

    @Transactional
    public void failed(UUID user, String feature, String requestKey) {
        jdbc.update(
                "UPDATE ai_usage_reservations SET status='FAILED',finished_at=now() WHERE user_id=? AND feature=? AND request_key=? AND status='RESERVED'",
                user,
                feature,
                requestKey);
    }

    private Integer tokenCount(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number n) || n.longValue() < 0 || n.longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid token count");
        return n.intValue();
    }
}
