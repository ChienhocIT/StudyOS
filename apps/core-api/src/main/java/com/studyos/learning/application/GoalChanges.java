package com.studyos.learning.application;

import com.studyos.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Validates a complete change set before persistence, preserving explicit nullable-field clears.
 */
final class GoalChanges {
    private static final Set<String> FIELDS =
            Set.of("title", "description", "targetDate", "weeklyMinutes", "status");
    private static final Set<String> STATUSES =
            Set.of("ACTIVE", "PAUSED", "COMPLETED", "CANCELLED");

    private GoalChanges() {}

    static Map<String, Object> validated(Map<String, Object> changes) {
        if (changes == null
                || changes.keySet().stream().anyMatch(key -> key == null || !FIELDS.contains(key)))
            throw invalid("Unknown goal field.");
        Map<String, Object> result = new LinkedHashMap<>(changes);
        if (changes.containsKey("title")) {
            Object value = changes.get("title");
            if (!(value instanceof String title) || title.isBlank() || title.length() > 240)
                throw invalid("Goal title must contain 1 to 240 characters.");
            result.put("title", ((String) value).strip());
        }
        Object description = changes.get("description");
        if (description != null && (!(description instanceof String text) || text.length() > 10000))
            throw invalid("Goal description must contain at most 10000 characters.");
        if (changes.containsKey("status")) {
            Object value = changes.get("status");
            if (!(value instanceof String status) || !STATUSES.contains(status))
                throw invalid("Invalid goal status.");
        }
        Object minutes = changes.get("weeklyMinutes");
        if (minutes != null) {
            if (!(minutes instanceof Number))
                throw invalid("Weekly minutes must be an integer between 15 and 10080.");
            try {
                int exact = new BigDecimal(minutes.toString()).intValueExact();
                if (exact < 15 || exact > 10080)
                    throw invalid("Weekly minutes must be between 15 and 10080.");
                result.put("weeklyMinutes", exact);
            } catch (ArithmeticException | NumberFormatException e) {
                throw invalid("Weekly minutes must be an integer between 15 and 10080.");
            }
        }
        Object date = changes.get("targetDate");
        if (date != null) {
            if (!(date instanceof String text) || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw invalid("Target date must be a valid date in YYYY-MM-DD format.");
            try {
                LocalDate parsed = LocalDate.parse((String) date);
                if (parsed.getYear() < 1)
                    throw invalid("Target date must have a year between 0001 and 9999.");
                result.put("targetDate", parsed.toString());
            } catch (DateTimeParseException e) {
                throw invalid("Target date must be a valid date in YYYY-MM-DD format.");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("VALIDATION_FAILED", message);
    }
}
