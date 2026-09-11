package com.studyos.studio.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuizScorerTest {
    @Test
    void shortAnswersNormalizeUnicodeCaseAndWhitespace() {
        assertThat(QuizScorer.correct("SHORT_ANSWER", "Isolation Level", "  isolation   level "))
                .isTrue();
    }

    @Test
    void multipleSelectionRequiresExactSetWithoutDuplicateCredit() {
        assertThat(QuizScorer.correct("MULTI_SELECT", List.of("A", "C"), List.of("C", "A")))
                .isTrue();
        assertThat(QuizScorer.correct("MULTI_SELECT", List.of("A", "C"), List.of("A"))).isFalse();
        assertThat(QuizScorer.correct("MULTI_SELECT", List.of("A", "C"), List.of("A", "C", "C")))
                .isFalse();
    }

    @Test
    void booleansDoNotAcceptTruthyStrings() {
        assertThat(QuizScorer.correct("TRUE_FALSE", true, "true")).isFalse();
        assertThat(QuizScorer.correct("TRUE_FALSE", true, true)).isTrue();
    }

    @Test
    void unansweredQuestionsAreIncorrect() {
        assertThat(QuizScorer.correct("MCQ", "A", null)).isFalse();
    }
}
