package com.studyos.review.domain;

import java.time.Instant;

/** Four-grade deterministic baseline. Intervals are bounded and independent of an LLM. */
public final class ConservativeReviewScheduler implements ReviewScheduler {
    public Result schedule(State previous, int grade, Instant reviewedAt) {
        if (grade < 1 || grade > 4 || reviewedAt == null)
            throw new IllegalArgumentException("Invalid review grade/time");
        double ease =
                Math.clamp(
                        previous.ease()
                                + (grade == 1 ? -0.2 : grade == 2 ? -0.15 : grade == 4 ? 0.15 : 0),
                        1.3,
                        3.0);
        long seconds;
        if (grade == 1) seconds = 60;
        else if (previous.repetitions() == 0)
            seconds = grade == 2 ? 600 : grade == 3 ? 86400 : 345600;
        else
            seconds =
                    Math.max(
                            grade == 2 ? 3600 : 86400,
                            Math.round(
                                    previous.intervalSeconds()
                                            * (grade == 2 ? 1.2 : grade == 3 ? ease : ease * 1.3)));
        seconds = Math.min(seconds, 365L * 86400);
        return new Result(
                new State(
                        grade == 1 ? 0 : previous.repetitions() + 1,
                        previous.lapses() + (grade == 1 ? 1 : 0),
                        ease,
                        seconds),
                reviewedAt.plusSeconds(seconds));
    }
}
