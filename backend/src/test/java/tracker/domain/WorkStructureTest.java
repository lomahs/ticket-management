package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class WorkStructureTest {
    static final TaskTemplate FUT_TC = new TaskTemplate("FUT create test case", "FUT", "FUTTC");
    static final TaskTemplate QNA = new TaskTemplate("QnA management for *", "FUT", "", false);
    static final WorkStructure WS = new WorkStructure(List.of("Study", "UC", "FUT"),
            List.of(FUT_TC, QNA, new TaskTemplate("Study & Estimation", "Study", "SE")));

    @Test
    void taskMatchesIgnoringCaseAndSpacing() {
        assertThat(WS.taskTemplate("  fut  Create test CASE ")).isEqualTo(FUT_TC);
    }

    @Test
    void starMatchesAnyTextAndEverythingElseIsLiteral() {
        assertThat(WS.taskTemplate("QnA management for FUT")).isEqualTo(QNA);
        assertThat(WS.taskTemplate("Study & Estimation").prefix()).isEqualTo("SE");
        WorkStructure special = new WorkStructure(List.of(), List.of(new TaskTemplate("[FUT] create (TC).*", "FUT", "")));
        assertThat(special.taskTemplate("[FUT] create (TC).*")).isNotNull();
        assertThat(special.taskTemplate("[FUT] create (TC).x")).isNotNull(); // only the trailing * is a wildcard
        assertThat(special.taskTemplate("F create TC")).isNull();
    }

    @Test
    void theWholeSummaryMustMatch() {
        assertThat(WS.taskTemplate("FUT create test case v2")).isNull();
    }

    @Test
    void firstMatchingTemplateWins() {
        WorkStructure ws = new WorkStructure(List.of(),
                List.of(new TaskTemplate("FUT *", "FUT", "A"), new TaskTemplate("FUT create test case", "FUT", "B")));
        assertThat(ws.taskTemplate("FUT create test case").prefix()).isEqualTo("A");
        assertThat(ws.templateIndex("FUT create test case")).isZero();
        assertThat(ws.templateIndex("Other")).isEqualTo(-1);
    }

    @Test
    void phaseFallsBackToBracketPrefixWithoutATemplate() {
        assertThat(WS.phaseOfTask("FUT create test case")).isEqualTo("FUT");
        assertThat(WS.phaseOfTask("[Implement_Coding] Payment flow revision")).isEqualTo("Implement_Coding");
        assertThat(WS.phaseOfTask("Work Request management")).isEmpty();
    }

    @Test
    void workflowIsOnUnlessTheTemplateTurnsItOff() {
        assertThat(WS.hasWorkflow("FUT create test case")).isTrue();
        assertThat(WS.hasWorkflow("QnA management for UC")).isFalse();
        assertThat(WS.hasWorkflow("Unknown task")).isTrue();
    }

    @Test
    void phasesFollowTheConfiguredOrderThenAlphabetical() {
        assertThat(WS.orderedPhases(List.of("FUT", "Zeta", "Study", "Alpha"))).containsExactly("Study", "FUT", "Alpha", "Zeta");
        assertThat(WorkStructure.empty().orderedPhases(List.of("b", "a", "b"))).containsExactly("a", "b");
    }
}
