package tracker.adapters.fake;

import java.util.ArrayList;
import java.util.List;

/** A Jira issue in the simulated site. Assignees and worklog authors are member ids; statuses are N/W/R/C. */
public final class FakeIssue {
    /** date, member, hours, comment */
    public record Log(String date, String member, double hours, String comment) {
    }

    /** at, field, from, to */
    public record Change(String at, String field, String from, String to) {
    }

    final String key;
    final String summary;
    final String parent;
    String status;
    String assignee;
    String reporter;
    String ps;
    String pe;
    double estH;
    final String created;
    String updated = Dataset.UPDATED;
    final List<Log> worklogs;
    final List<Change> changes;
    /** The project key as the one label, like the team's rule; the demo leaves a few without one. */
    List<String> labels;

    FakeIssue(String key, String summary, String parent, String status, String assignee, String ps, String pe, double estH,
              String created, List<Log> worklogs, List<Change> changes) {
        this.key = key;
        this.summary = summary;
        this.parent = parent;
        this.status = status;
        this.assignee = assignee;
        this.ps = ps;
        this.pe = pe;
        this.estH = estH;
        this.created = created;
        this.worklogs = new ArrayList<>(worklogs);
        this.changes = new ArrayList<>(changes);
        this.labels = List.of(key.split("-", -1)[0]);
    }

    FakeIssue(String key, String summary, String parent, String status, String assignee, String ps, String pe) {
        this(key, summary, parent, status, assignee, ps, pe, 0, "2026-07-01", List.of(), List.of());
    }

    FakeIssue(String key, String summary, String parent, String status, String assignee, String ps, String pe, double estH,
              String created) {
        this(key, summary, parent, status, assignee, ps, pe, estH, created, List.of(), List.of());
    }

    String project() {
        return key.split("-", -1)[0];
    }
}
