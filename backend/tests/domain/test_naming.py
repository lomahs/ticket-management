import pytest

from tracker.domain.model import StepKind
from tracker.domain.naming import (
    DEFAULT_ALIASES, DEFAULT_RULE, RuleError, compile_rule, coverage, parse_phase, parse_summary, render_summary,
)


def test_parses_default_pattern_with_alias():
    p = parse_summary(compile_rule(DEFAULT_RULE), "[Implement_Coding][F03] Domestic card payment – Fix comment BAL", DEFAULT_ALIASES)
    assert p.values == {"phase_name": "Implement_Coding", "function_id": "F03", "function_name": "Domestic card payment", "step": "Fix comment BAL"}
    assert p.step == StepKind.FIX_BAL


def test_hyphen_and_whitespace_are_lenient():
    p = parse_summary(compile_rule(DEFAULT_RULE), "[Prepare][K1]  Face match-Fix JM", DEFAULT_ALIASES)
    assert p.step == StepKind.FIX_JM and p.values["function_name"] == "Face match"


def test_hyphen_inside_function_name_splits_early_and_step_does_not_resolve():
    p = parse_summary(compile_rule(DEFAULT_RULE), "[Implement_Coding][F04] E-wallet payment – Create", DEFAULT_ALIASES)
    assert p is not None and p.step is None  # same behaviour as ui-mock-v0


def test_non_matching_summary_returns_none():
    assert parse_summary(compile_rule(DEFAULT_RULE), "UT – F01 Payment method selection", DEFAULT_ALIASES) is None


@pytest.mark.parametrize("rule,msg", [
    ("[{phase}] {step}", "Unknown placeholder {phase}"),
    ("{step} {step}", "{step} is used twice"),
    ("[{function_id}] {function_name}", "Pattern must contain {step}"),
])
def test_rule_errors(rule, msg):
    with pytest.raises(RuleError, match=msg.replace("{", r"\{").replace("}", r"\}")):
        compile_rule(rule)


def test_coverage_counts_summaries_with_a_known_step():
    s = ["[P][F1] A – Create", "[P][F2] E-wallet – Create", "nonsense"]
    assert coverage(compile_rule(DEFAULT_RULE), DEFAULT_ALIASES, s) == 1


def test_phase_from_parent_summary():
    assert parse_phase("[Implement_Coding] Payment flow revision") == "Implement_Coding"
    assert parse_phase("Payment flow revision") == ""


def test_render_summary_fills_the_rule():
    assert render_summary(DEFAULT_RULE, phase_name="Implement_Coding", function_id="F13", function_name="Installment",
                          step="Create") == "[Implement_Coding][F13] Installment – Create"
