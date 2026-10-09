"""The 5-step function workflow: Create → BAL Review → Fix comment BAL → JM Review → Fix comment JM."""

from dataclasses import dataclass

from .model import Role, StepKind


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
