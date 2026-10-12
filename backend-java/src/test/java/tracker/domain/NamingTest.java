package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class NamingTest {
    static final Naming.CompiledRule RULE = Naming.compileRule(Naming.DEFAULT_RULE, Naming.DEFAULT_ALIASES);

    @Test
    void parsesDefaultPatternWithAlias() {
        Naming.Parsed p = Naming.parseSummary(RULE, "[Implement_Coding][F03] Domestic card payment – Fix comment BAL");
        assertThat(p.values()).containsExactly(Map.entry("phase_name", "Implement_Coding"), Map.entry("function_id", "F03"),
                Map.entry("function_name", "Domestic card payment"), Map.entry("step", "Fix comment BAL"));
        assertThat(p.step()).isEqualTo(StepKind.FIX_BAL);
    }

    @Test
    void hyphenAndWhitespaceAreLenient() {
        Naming.Parsed p = Naming.parseSummary(RULE, "[Prepare][K1]  Face match-Fix JM");
        assertThat(p.step()).isEqualTo(StepKind.FIX_JM);
        assertThat(p.values().get("function_name")).isEqualTo("Face match");
    }

    @Test
    void hyphenInsideFunctionNameIsKeptBecauseStepMustBeAnAlias() {
        Naming.Parsed p = Naming.parseSummary(RULE, "[Implement_Coding][F04] E-wallet payment – Create");
        assertThat(p.values().get("function_name")).isEqualTo("E-wallet payment");
        assertThat(p.step()).isEqualTo(StepKind.CREATE);
    }

    @Test
    void stepTextThatIsNoAliasDoesNotMatch() {
        assertThat(Naming.parseSummary(RULE, "[Implement_Coding][F04] Payment – Deploy")).isNull();
    }

    @Test
    void aliasesMatchCaseInsensitively() {
        assertThat(Naming.parseSummary(RULE, "[P][F1] X – fix bal").step()).isEqualTo(StepKind.FIX_BAL);
    }

    @Test
    void nonMatchingSummaryReturnsNull() {
        assertThat(Naming.parseSummary(RULE, "UT – F01 Payment method selection")).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[{phase}] {step}|Unknown placeholder {phase}",
            "{step} {step}|{step} is used twice",
            "[{function_id}] {function_name}|Pattern must contain {step}"})
    void ruleErrors(String rule, String message) {
        assertThatThrownBy(() -> Naming.compileRule(rule, Naming.DEFAULT_ALIASES)).isInstanceOf(RuleError.class).hasMessage(message);
    }

    @Test
    void coverageCountsSummariesWithAKnownStep() {
        assertThat(Naming.coverage(RULE, List.of("[P][F1] A – Create", "[P][F2] E-wallet – Create", "[P][F3] A – Deploy", "nonsense")))
                .isEqualTo(2);
    }

    @Test
    void phaseFromParentSummary() {
        assertThat(Naming.parsePhase("[Implement_Coding] Payment flow revision")).isEqualTo("Implement_Coding");
        assertThat(Naming.parsePhase("Payment flow revision")).isEmpty();
    }

    @Test
    void renderSummaryFillsTheRule() {
        assertThat(Naming.renderSummary(Naming.DEFAULT_RULE, Map.of("phase_name", "Implement_Coding", "function_id", "F13",
                "function_name", "Installment", "step", "Create"))).isEqualTo("[Implement_Coding][F13] Installment – Create");
    }

    @Test
    void stepHintFromSummarySuffix() {
        assertThat(Steps.stepHint("Campaign export – Create")).isEqualTo(StepKind.CREATE);
        assertThat(Steps.stepHint("Loyalty tier spec – BAL Review")).isEqualTo(StepKind.BAL_REVIEW);
        assertThat(Steps.stepHint("Internal meeting / support")).isNull();
    }

    @Test
    void customAliasesWithUnicodeMatch() {
        Map<StepKind, String> aliases = new java.util.LinkedHashMap<>(Naming.DEFAULT_ALIASES);
        aliases.put(StepKind.CREATE, "Create, Tạo");
        Naming.CompiledRule r = Naming.compileRule("{function_id}-{step}", aliases);
        assertThat(Naming.parseSummary(r, "F01-tạo").step()).isEqualTo(StepKind.CREATE);
        assertThat(r.tokens()).containsExactly("function_id", "step");
    }
}
