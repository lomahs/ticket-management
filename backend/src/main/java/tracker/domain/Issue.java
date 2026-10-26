package tracker.domain;

import java.util.List;

/**
 * @param reporterId the first reporter: the one Jira had before any reporter change
 * @param labels     the Jira labels
 */
public record Issue(String key, String project, String epicKey, String parentKey, IssueKind kind, String summary,
                    String phase, Status status, String assigneeId, String plannedStart, String plannedEnd,
                    double estimateH, double remainingH, String created, String jiraStatus, String functionId, String functionName,
                    StepKind step, List<Worklog> worklogs, List<IssueEvent> events, String reporterId, List<String> labels) {

    public Issue {
        worklogs = List.copyOf(worklogs);
        events = List.copyOf(events);
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    public Issue(String key, String project, String epicKey, String parentKey, IssueKind kind, String summary, String phase,
                 Status status, String assigneeId, String plannedStart, String plannedEnd, double estimateH, double remainingH,
                 String created, String jiraStatus, String functionId, String functionName, StepKind step, List<Worklog> worklogs,
                 List<IssueEvent> events, String reporterId) {
        this(key, project, epicKey, parentKey, kind, summary, phase, status, assigneeId, plannedStart, plannedEnd, estimateH,
                remainingH, created, jiraStatus, functionId, functionName, step, worklogs, events, reporterId, List.of());
    }

    public Issue(String key, String project, String epicKey, String parentKey, IssueKind kind, String summary, String phase,
                 Status status, String assigneeId, String plannedStart, String plannedEnd, double estimateH, double remainingH,
                 String created, String jiraStatus, String functionId, String functionName, StepKind step, List<Worklog> worklogs,
                 List<IssueEvent> events) {
        this(key, project, epicKey, parentKey, kind, summary, phase, status, assigneeId, plannedStart, plannedEnd, estimateH,
                remainingH, created, jiraStatus, functionId, functionName, step, worklogs, events, null);
    }

    /** The first assignee: the one before the first assignee change (or the first one set), else the current one. */
    public String firstAssigneeId() {
        for (IssueEvent e : events.stream().filter(x -> x.field() == EventField.ASSIGNEE)
                .sorted(java.util.Comparator.comparing(IssueEvent::at)).toList()) {
            if (e.fromValue() != null) {
                return e.fromValue();
            }
            if (e.toValue() != null) {
                return e.toValue();
            }
        }
        return assigneeId;
    }

    /** Everyone the issue was assigned to, oldest first: the first assignee, then each assignee change. */
    public List<String> assigneeHistory() {
        List<String> out = new java.util.ArrayList<>();
        String first = firstAssigneeId();
        if (first != null) {
            out.add(first);
        }
        events.stream().filter(e -> e.field() == EventField.ASSIGNEE && e.toValue() != null)
                .sorted(java.util.Comparator.comparing(IssueEvent::at)).forEach(e -> out.add(e.toValue()));
        return out;
    }

    /** A sub-task that is its own unit of work, outside the 5-step workflow: no function id, but a name from the rule. */
    public boolean single() {
        return kind == IssueKind.SUBTASK && functionId == null && functionName != null;
    }

    public double loggedH() {
        return worklogs.stream().mapToDouble(Worklog::hours).sum();
    }

    public Issue withWorklogs(List<Worklog> logs) {
        return new Issue(key, project, epicKey, parentKey, kind, summary, phase, status, assigneeId, plannedStart, plannedEnd,
                estimateH, remainingH, created, jiraStatus, functionId, functionName, step, logs, events, reporterId, labels);
    }
}
