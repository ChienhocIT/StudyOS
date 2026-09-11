package com.studyos.learning.domain;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MasteryPolicyTest {
    @Test void separatesMasteryFromEvidenceConfidence(){
        var empty=MasteryPolicy.project(BigDecimal.ZERO,BigDecimal.ZERO);
        assertThat(empty.score()).isEqualByComparingTo("0.5");assertThat(empty.confidence()).isZero();
        var quiz=MasteryPolicy.project(new BigDecimal("0.8"),new BigDecimal("0.8"));
        assertThat(quiz.score()).isEqualByComparingTo("0.7222");assertThat(quiz.confidence()).isEqualByComparingTo("0.2105");
    }
    @Test void repeatedFailedEvidenceReducesMasteryWithoutClaimingLowConfidence(){
        var failed=MasteryPolicy.project(BigDecimal.ZERO,new BigDecimal("8"));
        assertThat(failed.score()).isLessThan(new BigDecimal("0.1"));
        assertThat(failed.confidence()).isGreaterThan(new BigDecimal("0.7"));
    }
    @Test void rejectsInvalidEvidenceTotals(){assertThatThrownBy(()->MasteryPolicy.project(BigDecimal.TEN,BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);}
}
