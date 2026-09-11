package com.studyos.source.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SourceStateTest {
    @Test
    void staleEventsCannotRegressOrReviveTerminalSources() {
        assertThat(SourceState.QUEUED.canAdvanceTo(SourceState.NORMALIZED)).isTrue();
        assertThat(SourceState.ENRICHING.canAdvanceTo(SourceState.NORMALIZED)).isFalse();
        assertThat(SourceState.READY.canAdvanceTo(SourceState.FAILED)).isFalse();
        assertThat(SourceState.DELETING.canAdvanceTo(SourceState.READY)).isFalse();
        assertThat(SourceState.DELETED.canAdvanceTo(SourceState.READY)).isFalse();
        assertThat(SourceState.FAILED.canAdvanceTo(SourceState.READY)).isFalse();
        assertThat(SourceState.FAILED.canAdvanceTo(SourceState.QUEUED)).isTrue();
    }
}
