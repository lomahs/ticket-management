"""The 5-step function workflow: Create → BAL Review → Fix comment BAL → JM Review → Fix comment JM."""

from dataclasses import dataclass

from .model import Issue, Role, StepKind


@dataclass(frozen=True)
class StepDef:
    kind: StepKind
    label: str
    duration_days: int  # default plan length when scheduling a new function
    review: bool
    fix: bool
    effort_weight: float  # share of the Create estimate
    role: Role  # who is assigned this step when a function is created


STEPS: tuple[StepDef, ...] = (
    StepDef(StepKind.CREATE, "Create", 2, False, False, 1.0, Role.BA),
    StepDef(StepKind.BAL_REVIEW, "BAL Review", 1, True, False, 0.25, Role.BAL),
    StepDef(StepKind.FIX_BAL, "Fix comment BAL", 1, False, True, 0.3, Role.BA),
    StepDef(StepKind.JM_REVIEW, "JM Review", 2, True, False, 0.25, Role.JM),
    StepDef(StepKind.FIX_JM, "Fix comment JM", 1, False, True, 0.2, Role.BA),
)
STEP_INDEX: dict[StepKind, int] = {s.kind: i for i, s in enumerate(STEPS)}
STEP_BY_KIND: dict[StepKind, StepDef] = {s.kind: s for s in STEPS}


def step_def(issue: Issue) -> StepDef | None:
    """The workflow step of a function sub-task; None for any other issue."""
    return STEP_BY_KIND[issue.step] if issue.function_id is not None and issue.step is not None else None


def is_fix_step(issue: Issue) -> bool:
    step = step_def(issue)
    return step is not None and step.fix


def step_hint(summary: str) -> StepKind | None:
    """For tasks outside the naming rule: the step their Summary ends with ("… – BAL Review"), if any."""
    s = summary.strip().lower()
    for st in sorted(STEPS, key=lambda x: -len(x.label)):
        if s.endswith(st.label.lower()):
            return st.kind
    return None
