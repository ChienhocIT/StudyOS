package com.studyos.learning.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Explainable evidence aggregation; conversation and page views supply no evidence. */
public final class MasteryPolicy {
    public static final String VERSION = "mastery-v0";
    private MasteryPolicy() {}

    public record Projection(BigDecimal score, BigDecimal confidence) {}

    public static Projection project(BigDecimal weightedScores, BigDecimal totalWeight) {
        if (totalWeight.signum() < 0 || weightedScores.signum() < 0
                || weightedScores.compareTo(totalWeight) > 0) {
            throw new IllegalArgumentException("Invalid evidence totals");
        }
        return new Projection(
                weightedScores.add(new BigDecimal("0.5"))
                        .divide(totalWeight.add(BigDecimal.ONE), 4, RoundingMode.HALF_UP),
                totalWeight.divide(totalWeight.add(new BigDecimal("3")), 4, RoundingMode.HALF_UP));
    }
}
