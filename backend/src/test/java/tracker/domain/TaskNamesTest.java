package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaskNamesTest {
    static final TaskTemplate PREPARE = new TaskTemplate("[*][LocalTask] ITA Prepare", "ITA", "");
    static final TaskTemplate QNA = new TaskTemplate("[Prepare] QnA management", "Prepare", "", false);
    static final WorkStructure ST = new WorkStructure(List.of("Prepare", "ITA"), List.of(PREPARE, QNA));

    @Test
    void taskIsTheMatchWithoutLeadingBracketsAndStars() {
        assertThat(TaskNames.task(PREPARE)).isEqualTo("ITA Prepare");
        assertThat(TaskNames.task(QNA)).isEqualTo("QnA management");
        assertThat(TaskNames.task(new TaskTemplate("*ITA  Execute*", "ITA", ""))).isEqualTo("ITA Execute");
    }

    @Test
    void renderFillsTheTokens() {
        assertThat(TaskNames.render("[{anken_prefix}][LocalTask] {task}", "BookUI", PREPARE)).isEqualTo("[BookUI][LocalTask] ITA Prepare");
        assertThat(TaskNames.render("[{phase}][{anken_prefix}] {task}", "REV", QNA)).isEqualTo("[Prepare][REV] QnA management");
    }

    @Test
    void proposalKeepsTheRuleWhenItMatchesItsTemplate() {
        assertThat(TaskNames.propose(TaskNames.DEFAULT_RULE, "BookUI", PREPARE, ST)).isEqualTo("[BookUI][LocalTask] ITA Prepare");
    }

    @Test
    void proposalFallsBackToTheMatchWithThePrefixForStars() {
        // the rule's name would not be recognised as QnA management: the template's own name is used
        assertThat(TaskNames.propose(TaskNames.DEFAULT_RULE, "BookUI", QNA, ST)).isEqualTo("[Prepare] QnA management");
        assertThat(TaskNames.propose("{task}", "BookUI", PREPARE, ST)).isEqualTo("[BookUI][LocalTask] ITA Prepare");
    }

    @Test
    void ruleMustNameTheTask() {
        assertThatThrownBy(() -> TaskNames.check("[{anken_prefix}] Prepare")).isInstanceOf(RuleError.class).hasMessageContaining("{task}");
        assertThatThrownBy(() -> TaskNames.check("{task} {who}")).isInstanceOf(RuleError.class).hasMessageContaining("{who}");
        TaskNames.check(TaskNames.DEFAULT_RULE);
    }
}
