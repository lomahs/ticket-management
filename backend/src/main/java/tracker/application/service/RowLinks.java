package tracker.application.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tracker.application.TrackerContext;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.PlanRows;
import tracker.domain.Text;

/**
 * The unpushed estimation rows whose function is already in Jira: a sub-task under the row's Task with the row's function name
 * (as the Function Matrix has a function: its name under its Task) and the step's kind, or for a ticket a single sub-task. Jira's
 * plan stands for the steps it has (the step of the row shows no more and is not created again); a row whose steps are all in
 * Jira (or removed) is pushed.
 *
 * @param issueOfStep a row step's ref → its sub-task in Jira
 * @param rowOfIssue  a sub-task in Jira → the row it stands for a step of
 * @param chainOfRow  a row with a step in Jira → its function's chain in Jira, so its steps are ordered with Jira's
 * @param whole       the rows with every step in Jira or removed, and at least one in Jira
 */
record RowLinks(Map<String, String> issueOfStep, Map<String, Long> rowOfIssue, Map<Long, String> chainOfRow, Set<Long> whole) {
    static RowLinks of(TrackerContext ctx, String epic, List<EstimationRow> rows, Map<Long, List<PlanRows.RowStep>> stepsOf) {
        List<Issue> subs = ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic) && Text.hasText(i.parentKey())).toList();
        Map<String, String> issueOfStep = new HashMap<>();
        Map<String, Long> rowOfIssue = new HashMap<>();
        Map<Long, String> chainOfRow = new HashMap<>();
        Set<Long> whole = new LinkedHashSet<>();
        for (EstimationRow r : rows) {
            if (!Text.hasText(r.taskKey()) || !Text.hasText(r.functionName())) {
                continue;
            }
            String name = r.functionName().strip();
            boolean all = true;
            for (PlanRows.RowStep st : stepsOf.getOrDefault(r.id(), List.of())) {
                Issue found = subs.stream().filter(i -> i.parentKey().equals(r.taskKey()) && i.step() == st.kind()
                        && name.equalsIgnoreCase(Text.orElse(Text.orElse(i.functionId(), i.functionName()), "").strip())
                        && !rowOfIssue.containsKey(i.key())).findFirst().orElse(null);
                if (found != null) {
                    issueOfStep.put(st.ref(), found.key());
                    rowOfIssue.put(found.key(), r.id());
                    chainOfRow.putIfAbsent(r.id(), PlanLines.chain(found));
                } else if (!st.removed()) {
                    all = false;
                }
            }
            if (all && chainOfRow.containsKey(r.id())) {
                whole.add(r.id());
            }
        }
        return new RowLinks(issueOfStep, rowOfIssue, chainOfRow, whole);
    }

    /** The steps of a row Jira does not have yet. */
    List<PlanRows.RowStep> open(List<PlanRows.RowStep> steps) {
        return steps.stream().filter(st -> !issueOfStep.containsKey(st.ref())).toList();
    }
}
