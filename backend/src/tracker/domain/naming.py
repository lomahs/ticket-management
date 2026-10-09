"""Sub-task Summary naming rule: placeholders are read as values, everything else must match literally."""

import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass

from .model import StepKind

TOKENS: dict[str, str] = {
    "anken_name": "Anken name",
    "os_task": "OS task",
    "phase_name": "Phase",
    "function_id": "Function ID",
    "function_name": "Function name",
    "step": "Step",
}
DEFAULT_RULE = "[{phase_name}][{function_id}] {function_name} – {step}"
DEFAULT_ALIASES: dict[StepKind, str] = {
    StepKind.CREATE: "Create",
    StepKind.BAL_REVIEW: "BAL Review",
    StepKind.FIX_BAL: "Fix comment BAL, Fix BAL",
    StepKind.JM_REVIEW: "JM Review",
    StepKind.FIX_JM: "Fix comment JM, Fix JM",
}

_PLACEHOLDER = re.compile(r"(\{[a-z_]+\})")


class RuleError(ValueError):
    pass


@dataclass(frozen=True)
class CompiledRule:
    regex: re.Pattern[str]
    tokens: tuple[str, ...]
    steps: dict[str, StepKind]  # normalised alias → step


@dataclass(frozen=True)
class Parsed:
    values: dict[str, str]
    step: StepKind


def _literal(ch: str) -> str:
    if ch.isspace():
        return r"\s*"
    if ch in "–-":
        return "[–-]"
    return re.escape(ch)


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s.lower()).replace("–", "-")


def compile_rule(rule: str, aliases: Mapping[StepKind, str]) -> CompiledRule:
    """{step} only matches a known alias, so a hyphen inside a function name cannot be mistaken for the separator."""
    steps = {_norm(n): kind for kind, names in aliases.items() for n in names.split(",") if n.strip()}
    names = sorted({n.strip() for names in aliases.values() for n in names.split(",") if n.strip()}, key=len, reverse=True)
    step_src = "(?i:" + "|".join("".join(_literal(ch) for ch in n) for n in names) + ")" if names else "(?!)"
    seen: list[str] = []
    src = ""
    for part in _PLACEHOLDER.split(rule):
        m = re.fullmatch(r"\{([a-z_]+)\}", part)
        if m:
            name = m.group(1)
            if name not in TOKENS:
                raise RuleError(f"Unknown placeholder {{{name}}}")
            if name in seen:
                raise RuleError(f"{{{name}}} is used twice")
            seen.append(name)
            src += f"(?P<step>{step_src})" if name == "step" else f"(?P<{name}>.+?)"
        else:
            src += "".join(_literal(ch) for ch in part)
    if "step" not in seen:
        raise RuleError("Pattern must contain {step}")
    return CompiledRule(re.compile(rf"^\s*{src}\s*$"), tuple(seen), steps)


def parse_summary(rule: CompiledRule, summary: str) -> Parsed | None:
    m = rule.regex.match(summary)
    if not m:
        return None
    values = {k: v for k, v in m.groupdict().items() if v is not None}
    return Parsed(values, rule.steps[_norm(values["step"])])


def coverage(rule: CompiledRule, summaries: Iterable[str]) -> int:
    """How many summaries match the rule."""
    return sum(1 for s in summaries if parse_summary(rule, s))


def parse_phase(parent_summary: str) -> str:
    """Phase name from a parent ticket Summary such as "[Implement_Coding] Payment flow revision"."""
    m = re.match(r"^\s*\[([^\]]+)\]", parent_summary)
    return m.group(1) if m else ""


def render_summary(rule: str, **values: str) -> str:
    """Fills a rule with values; unknown or missing placeholders become empty."""
    return _PLACEHOLDER.sub(lambda m: values.get(m.group(1)[1:-1], ""), rule)
