package com.studyos.review.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReviewSchedulerTest {
    private final ReviewScheduler scheduler = new ConservativeReviewScheduler();
    private final Instant now = Instant.parse("2026-09-11T00:00:00Z");

    @Test
    void gradesProduceIncreasingInitialIntervals() {
        long prior = 0;
        for (int grade = 1; grade <= 4; grade++) {
            var result = scheduler.schedule(ReviewScheduler.State.initial(), grade, now);
            assertThat(result.state().intervalSeconds()).isGreaterThan(prior);
            prior = result.state().intervalSeconds();
            assertThat(result.dueAt()).isAfter(now);
        }
    }

    @Test
    void lapseResetsRepetitionsAndSchedulesImmediateRelearning() {
        var result = scheduler.schedule(new ReviewScheduler.State(5, 1, 2.5, 864000), 1, now);
        assertThat(result.state().repetitions()).isZero();
        assertThat(result.state().lapses()).isEqualTo(2);
        assertThat(result.dueAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void allRepeatedGradesStayWithinBounds() {
        for (int grade = 1; grade <= 4; grade++) {
            var state = ReviewScheduler.State.initial();
            for (int i = 0; i < 1000; i++) {
                state = scheduler.schedule(state, grade, now).state();
                assertThat(state.ease()).isBetween(1.3, 3.0);
                assertThat(state.intervalSeconds()).isBetween(60L, 365L * 86400);
            }
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> scheduler.schedule(ReviewScheduler.State.initial(), 0, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
