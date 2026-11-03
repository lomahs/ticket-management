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
    /** The demo's pattern: a dash before the step. */
    static final Naming.CompiledRule DASH = Naming.compileRule("[{sub_phase_prefix}] {function} – {step?}", Naming.DEFAULT_ALIASES);

    @Test
    void parsesDefaultPatternWithAlias() {
        Naming.Parsed p = Naming.parseSummary(RULE, "[BookUI][LocalTask][ITA_Exe]_Domestic card payment_Fix comment BAL");
        assertThat(p.values()).containsExactly(Map.entry("anken_prefix", "BookUI"), Map.entry("os_task", "LocalTask"),
                Map.entry("sub_phase_prefix", "ITA_Exe"), Map.entry("function", "Domestic card payment"), Map.entry("step", "Fix comment BAL"));
        assertThat(p.step()).isEqualTo(StepKind.FIX_BAL);
    }

    @Test
    void hyphenAndWhitespaceAreLenient() {
        Naming.Parsed p = Naming.parseSummary(DASH, "[Prepare]  Face match-Fix JM");
        assertThat(p.step()).isEqualTo(StepKind.FIX_JM);
        assertThat(p.values().get("function")).isEqualTo("Face match");
    }

    @Test
    void hyphenInsideFunctionNameIsKeptBecauseStepMustBeAnAlias() {
        Naming.Parsed p = Naming.parseSummary(DASH, "[Implement_Coding] E-wallet payment – Create");
        assertThat(p.values().get("function")).isEqualTo("E-wallet payment");
        assertThat(p.step()).isEqualTo(StepKind.CREATE);
    }

    @Test
    void anOptionalStepMayBeMissingTogetherWithTheTextBeforeIt() {
        Naming.Parsed step = Naming.parseSummary(DASH, "[Implement_Coding] E-wallet payment – Create");
        assertThat(List.of(step.values().get("function"), step.step())).containsExactly("E-wallet payment", StepKind.CREATE);
        Naming.Parsed single = Naming.parseSummary(DASH, "[Prepare] Requirement study");
        assertThat(single.step()).isNull();
        assertThat(single.values()).containsExactly(Map.entry("sub_phase_prefix", "Prepare"), Map.entry("function", "Requirement study"));
        assertThat(Naming.itemName(single, "[Prepare] Requirement study")).isEqualTo("Requirement study");
        // a dash that is no step separator stays in the name
        assertThat(Naming.parseSummary(DASH, "[Prepare] Study – payment flow").values().get("function")).isEqualTo("Study – payment flow");
        assertThat(Naming.parseSummary(DASH, "Kick-off with JM")).isNull();
        assertThat(Naming.coverage(DASH, List.of("[Prepare] Requirement study", "[A] X – JM Review", "Other"))).isEqualTo(2);
        Naming.Parsed byDefault = Naming.parseSummary(RULE, "[BookUI][LocalTask][ITA_Prep]_Kick-off");
        assertThat(List.of(byDefault.values().get("function"), String.valueOf(byDefault.step()))).containsExactly("Kick-off", "null");
    }

    @Test
    void onlyTheStepCanBeOptional() {
        assertThatThrownBy(() -> Naming.compileRule("[{sub_phase_prefix?}] {function} – {step}", Naming.DEFAULT_ALIASES))
                .isInstanceOf(RuleError.class).hasMessageContaining("Only {step}");
        assertThatThrownBy(() -> Naming.compileRule("{function} – {step?} {step}", Naming.DEFAULT_ALIASES))
                .isInstanceOf(RuleError.class).hasMessageContaining("used twice");
        assertThat(Naming.renderSummary("[{sub_phase_prefix}] {function} – {step?}", Map.of("sub_phase_prefix", "P", "function", "F", "step", "Create")))
                .isEqualTo("[P] F – Create");
    }

    @Test
    void stepTextThatIsNoAliasDoesNotMatch() {
        assertThat(Naming.parseSummary(DASH, "[Implement_Coding] Payment – Deploy").step()).isNull(); // a single ticket, not a step
        assertThat(Naming.parseSummary(Naming.compileRule("[{sub_phase_prefix}] {function} – {step}", Naming.DEFAULT_ALIASES),
                "[Implement_Coding] Payment – Deploy")).isNull();
    }

    @Test
    void aliasesMatchCaseInsensitively() {
        assertThat(Naming.parseSummary(RULE, "[P][LocalTask][X]_Y_fix bal").step()).isEqualTo(StepKind.FIX_BAL);
    }

    @Test
    void nonMatchingSummaryReturnsNull() {
        assertThat(Naming.parseSummary(RULE, "UT – F01 Payment method selection")).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[{phase}] {step}|Unknown placeholder {phase}",
            "[{function_id}] {step}|Unknown placeholder {function_id}",
            "{step} {step}|{step} is used twice",
            "[{sub_phase_prefix}] {function}|Pattern must contain {step}"})
    void ruleErrors(String rule, String message) {
        assertThatThrownBy(() -> Naming.compileRule(rule, Naming.DEFAULT_ALIASES)).isInstanceOf(RuleError.class).hasMessage(message);
    }

    @Test
    void coverageCountsSummariesWithAKnownStep() {
        assertThat(Naming.coverage(RULE, List.of("[P][L][S]_A_Create", "[P][L][S]_E-wallet_Create", "nonsense"))).isEqualTo(2);
    }

    @Test
    void phaseFromParentSummary() {
        assertThat(Naming.parsePhase("[Implement_Coding] Payment flow revision")).isEqualTo("Implement_Coding");
        assertThat(Naming.parsePhase("Payment flow revision")).isEmpty();
    }

    @Test
    void renderSummaryFillsTheRule() {
        assertThat(Naming.renderSummary(Naming.DEFAULT_RULE, Map.of("anken_prefix", "BookUI", "os_task", "LocalTask", "sub_phase_prefix", "ITA_Exe",
                "function", "Installment", "step", "Create"))).isEqualTo("[BookUI][LocalTask][ITA_Exe]_Installment_Create");
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
        Naming.CompiledRule r = Naming.compileRule("{function}-{step}", aliases);
        assertThat(Naming.parseSummary(r, "F01-tạo").step()).isEqualTo(StepKind.CREATE);
        assertThat(r.tokens()).containsExactly("function", "step");
    }

    static final Map<StepKind, String> COMPANY_ALIASES = Map.of(StepKind.CREATE, "Create", StepKind.BAL_REVIEW, "BALReview",
            StepKind.FIX_BAL, "FixCommentBAL", StepKind.JM_REVIEW, "JMReview", StepKind.FIX_JM, "FixCommentJM");
    static final Naming.CompiledRule COMPANY = Naming.compileRule("[{sub_phase_prefix}][{function}]_{step}", COMPANY_ALIASES);

    @ParameterizedTest
    @CsvSource({"Create,create", "BALReview,balr", "FixCommentBAL,fixb", "JMReview,jmr", "FixCommentJM,fixj"})
    void companyRuleWithPrefix(String stepText, String step) {
        Naming.Parsed p = Naming.parseSummary(COMPANY, "[FUTTC][Login]_" + stepText);
        assertThat(p.values()).containsExactly(Map.entry("sub_phase_prefix", "FUTTC"), Map.entry("function", "Login"),
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
    void functionKeyIsTheName() {
        assertThat(Naming.functionKey(Naming.parseSummary(RULE, "[P][LocalTask][S]_Login_Create"))).isEqualTo("Login");
    }

    @Test
    void ruleMustNameTheFunction() {
        Naming.checkIdentity(COMPANY);
        Naming.checkIdentity(RULE);
        assertThatThrownBy(() -> Naming.checkIdentity(Naming.compileRule("[{sub_phase_prefix}] {step}", Naming.DEFAULT_ALIASES)))
                .isInstanceOf(RuleError.class).hasMessage("Pattern must contain {function}");
    }
}
