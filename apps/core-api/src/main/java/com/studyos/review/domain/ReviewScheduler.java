package com.studyos.review.domain;

import java.time.Instant;

public interface ReviewScheduler {
    record State(int repetitions, int lapses, double ease, long intervalSeconds) {
        public State {
            if (repetitions < 0
                    || lapses < 0
                    || !Double.isFinite(ease)
                    || ease < 1.3
                    || ease > 3.0
                    || intervalSeconds < 0)
                throw new IllegalArgumentException("Invalid scheduler state");
        }

        public static State initial() {
            return new State(0, 0, 2.5, 0);
        }
    }

    record Result(State state, Instant dueAt) {}

    Result schedule(State previous, int grade, Instant reviewedAt);
}
