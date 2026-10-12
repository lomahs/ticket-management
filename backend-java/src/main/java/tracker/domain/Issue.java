package tracker.domain;

import java.util.List;

public record Issue(String key, String project, String epicKey, String parentKey, IssueKind kind, String summary,
                    String phase, Status status, String assigneeId, String plannedStart, String plannedEnd,
                    double estimateH, String created, String jiraStatus, String functionId, String functionName,
                    StepKind step, List<Worklog> worklogs, List<IssueEvent> events) {

    public Issue {
        worklogs = List.copyOf(worklogs);
        events = List.copyOf(events);
    }

    public double loggedH() {
        return Num.sum(worklogs.stream().mapToDouble(Worklog::hours));
    }

    public Issue withWorklogs(List<Worklog> logs) {
        return new Issue(key, project, epicKey, parentKey, kind, summary, phase, status, assigneeId, plannedStart, plannedEnd,
                estimateH, created, jiraStatus, functionId, functionName, step, logs, events);
    }
}
