package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    static final Map<StepKind, String> COMPANY_ALIASES = Map.of(StepKind.CREATE, "Create", StepKind.BAL_REVIEW, "BALReview",
            StepKind.FIX_BAL, "FixCommentBAL", StepKind.JM_REVIEW, "JMReview", StepKind.FIX_JM, "FixCommentJM");
    static final Naming.CompiledRule COMPANY = Naming.compileRule("[{prefix}][{function_name}]_{step}", COMPANY_ALIASES);

    @ParameterizedTest
    @CsvSource({"Create,create", "BALReview,balr", "FixCommentBAL,fixb", "JMReview,jmr", "FixCommentJM,fixj"})
    void companyRuleWithPrefixAndNoFunctionId(String stepText, String step) {
        Naming.Parsed p = Naming.parseSummary(COMPANY, "[FUTTC][Login]_" + stepText);
        assertThat(p.values()).containsExactly(Map.entry("prefix", "FUTTC"), Map.entry("function_name", "Login"),
                Map.entry("step", stepText));
        assertThat(p.step()).isEqualTo(StepKind.of(step));
        assertThat(Naming.functionKey(p)).isEqualTo("Login");
    }

    @Test
    void functionNameMayContainUnderscores() {
        Naming.Parsed p = Naming.parseSummary(COMPANY, "[UCI][Login_Screen]_Create");
        assertThat(Naming.functionKey(p)).isEqualTo("Login_Screen");
        assertThat(p.step()).isEqualTo(StepKind.CREATE);
    }

    @Test
    void functionKeyPrefersTheId() {
        assertThat(Naming.functionKey(Naming.parseSummary(RULE, "[P][F1] Login – Create"))).isEqualTo("F1");
    }

    @Test
    void ruleMustNameTheFunction() {
        Naming.checkIdentity(COMPANY);
        Naming.checkIdentity(RULE);
        assertThatThrownBy(() -> Naming.checkIdentity(Naming.compileRule("[{phase_name}] {step}", Naming.DEFAULT_ALIASES)))
                .isInstanceOf(RuleError.class).hasMessage("Pattern must contain {function_id} or {function_name}");
    }
}
