package tracker.domain;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The 5-step function workflow: Create → BAL Review → Fix comment BAL → JM Review → Fix comment JM. */
public final class Steps {
    private Steps() {
    }

    public static final List<StepDef> STEPS = List.of(
            new StepDef(StepKind.CREATE, "Create", 2, false, false, 1.0, Role.BA),
            new StepDef(StepKind.BAL_REVIEW, "BAL Review", 1, true, false, 0.25, Role.BAL),
            new StepDef(StepKind.FIX_BAL, "Fix comment BAL", 1, false, true, 0.3, Role.BA),
            new StepDef(StepKind.JM_REVIEW, "JM Review", 2, true, false, 0.25, Role.JM),
            new StepDef(StepKind.FIX_JM, "Fix comment JM", 1, false, true, 0.2, Role.BA));
    public static final int COUNT = STEPS.size();
    private static final Map<StepKind, Integer> INDEX = new EnumMap<>(StepKind.class);
    private static final Map<StepKind, StepDef> BY_KIND = new EnumMap<>(StepKind.class);

    static {
        for (int i = 0; i < STEPS.size(); i++) {
            INDEX.put(STEPS.get(i).kind(), i);
            BY_KIND.put(STEPS.get(i).kind(), STEPS.get(i));
        }
    }

    public static int index(StepKind kind) {
        return INDEX.get(kind);
    }

    /** The workflow step of a function sub-task; null for any other issue. */
    public static StepDef stepDef(Issue issue) {
        return issue.functionId() != null && issue.step() != null ? BY_KIND.get(issue.step()) : null;
    }

    public static boolean isFixStep(Issue issue) {
        StepDef step = stepDef(issue);
        return step != null && step.fix();
    }

    /** For tasks outside the naming rule: the step their Summary ends with ("… – BAL Review"), if any. */
    public static StepKind stepHint(String summary) {
        String s = summary.strip().toLowerCase(java.util.Locale.ROOT);
        return STEPS.stream().sorted(Comparator.comparingInt((StepDef x) -> -x.label().length()))
                .filter(st -> s.endsWith(st.label().toLowerCase(java.util.Locale.ROOT)))
                .map(StepDef::kind).findFirst().orElse(null);
    }
}
