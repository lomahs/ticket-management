package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tracker.application.settings.TrackerSettings;

class StepWeightsTest {
    @Test
    void defaultsAreTheStepTable() {
        StepWeights w = StepWeights.defaults();
        assertThat(w.of(StepKind.CREATE)).isEqualTo(1.0);
        assertThat(w.of(StepKind.BAL_REVIEW)).isEqualTo(0.25);
        assertThat(w.of(StepKind.FIX_BAL)).isEqualTo(0.3);
        assertThat(w.of(StepKind.JM_REVIEW)).isEqualTo(0.25);
        assertThat(w.of(StepKind.FIX_JM)).isEqualTo(0.2);
    }

    @Test
    void baTotalIsTheBaShareOfEachStep() { // Create 8 + 70 % of Fix BAL 2.5 and Fix JM 1.5; the reviews are not the BA's
        StepWeights w = StepWeights.defaults();
        assertThat(w.baTotal(8, 5)).isCloseTo(10.8, within(1e-9));
        assertThat(w.baTotal(8, 3)).isCloseTo(9.75, within(1e-9));
        assertThat(w.stepHours(8, StepKind.JM_REVIEW)).isEqualTo(2.0);
    }

    @Test
    void stepsOfThreeAndFive() {
        assertThat(StepWeights.stepsOf(3)).containsExactly(StepKind.CREATE, StepKind.BAL_REVIEW, StepKind.FIX_BAL);
        assertThat(StepWeights.stepsOf(5)).containsExactly(StepKind.values());
        assertThatThrownBy(() -> StepWeights.stepsOf(4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ankenOverrideWinsOverDefault() {
        TrackerSettings s = new TrackerSettings();
        Map<StepKind, Double> custom = new EnumMap<>(StepWeights.defaults().weights());
        custom.put(StepKind.BAL_REVIEW, 0.5);
        TrackerSettings.AnkenSettings a = new TrackerSettings.AnkenSettings();
        a.stepWeights = new StepWeights(custom);
        s.anken.put("PAY-1100", a);
        s.anken.put("PAY-1300", new TrackerSettings.AnkenSettings());
        assertThat(s.stepWeightsOf("PAY-1100").of(StepKind.BAL_REVIEW)).isEqualTo(0.5);
        assertThat(s.stepWeightsOf("PAY-1300")).isEqualTo(StepWeights.defaults());
        assertThat(s.stepWeightsOf(null)).isEqualTo(StepWeights.defaults());
    }
}
