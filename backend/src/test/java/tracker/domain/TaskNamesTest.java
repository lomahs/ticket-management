package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaskNamesTest {
    static final TaskTemplate PREPARE = new TaskTemplate("[*][LocalTask] ITA Prepare", "ITA", "");
    static final TaskTemplate QNA = new TaskTemplate("[Prepare] QnA management", "Prepare", "", false);
    static final WorkStructure ST = new WorkStructure(List.of("Prepare", "ITA"), List.of(PREPARE, QNA));

    @Test
    void subPhaseIsTheMatchWithoutLeadingBracketsAndStars() {
        assertThat(TaskNames.subPhase(PREPARE)).isEqualTo("ITA Prepare");
        assertThat(TaskNames.subPhase(QNA)).isEqualTo("QnA management");
        assertThat(TaskNames.subPhase(new TaskTemplate("*ITA  Execute*", "ITA", ""))).isEqualTo("ITA Execute");
    }

    @Test
    void renderFillsTheTokensWithLocalTaskAsTheOsTask() {
        assertThat(TaskNames.render(TaskNames.DEFAULT_RULE, "BookUI", PREPARE)).isEqualTo("[BookUI][LocalTask] ITA Prepare");
        assertThat(TaskNames.render("[{anken_prefix}] {sub_phase}", "REV", QNA)).isEqualTo("[REV] QnA management");
    }

    @Test
    void recognisedTellsWhetherTheTemplateReadsTheRulesName() {
        assertThat(TaskNames.recognised(TaskNames.DEFAULT_RULE, "BookUI", PREPARE, ST)).isTrue();
        // "[BookUI][LocalTask] QnA management" is not "[Prepare] QnA management"
        assertThat(TaskNames.recognised(TaskNames.DEFAULT_RULE, "BookUI", QNA, ST)).isFalse();
    }

    @Test
    void parseReadsThePlaceholdersOfATaskSummary() {
        assertThat(TaskNames.parse(TaskNames.DEFAULT_RULE, "[BookUI][LocalTask]  ITA Prepare"))
                .containsExactly(entry("anken_prefix", "BookUI"), entry("os_task", "LocalTask"), entry("sub_phase", "ITA Prepare"));
        assertThat(TaskNames.ankenPrefix(TaskNames.DEFAULT_RULE, "[bookui][localtask] ITA Execute")).isEqualTo("bookui");
        assertThat(TaskNames.matches(TaskNames.DEFAULT_RULE, "[Prepare] QnA management")).isFalse();
        assertThat(TaskNames.ankenPrefix(TaskNames.DEFAULT_RULE, "WorkRequest")).isEmpty();
        assertThat(TaskNames.ankenPrefix("{sub_phase}", "[BookUI][LocalTask] ITA Prepare")).isEmpty();
    }

    @Test
    void ruleMustNameTheSubPhase() {
        assertThatThrownBy(() -> TaskNames.check("[{anken_prefix}] Prepare")).isInstanceOf(RuleError.class).hasMessageContaining("{sub_phase}");
        assertThatThrownBy(() -> TaskNames.check("{sub_phase} {task}")).isInstanceOf(RuleError.class).hasMessageContaining("{task}");
        assertThatThrownBy(() -> TaskNames.check("{sub_phase} {sub_phase}")).isInstanceOf(RuleError.class).hasMessageContaining("twice");
        TaskNames.check(TaskNames.DEFAULT_RULE);
    }
}
