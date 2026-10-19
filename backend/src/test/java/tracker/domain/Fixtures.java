package tracker.domain;

import java.util.ArrayList;
import java.util.List;

/** Small builders for domain objects used across domain tests. */
final class Fixtures {
    private Fixtures() {
    }

    static final String TODAY = "2026-10-07";

    /** A WIP sub-task of PAY-1100 planned 05/10–09/10 with an 8h estimate. */
    static final class I {
        String key = "PAY-1";
        Status status = Status.WIP;
        String assignee = "an";
        String ps = "2026-10-05";
        String pe = "2026-10-09";
        double est = 8.0;
        Double remaining;
        double logged = 0.0;
        String logDate;
        StepKind step;
        String fid;
        String fname = "Fn";
        String phase = "Implement_Coding";
        List<IssueEvent> events = new ArrayList<>();
        String created = "2026-09-01";
        String epic = "PAY-1100";
        String summary;
        String reporter;

        I key(String v) { key = v; return this; }
        I status(Status v) { status = v; return this; }
        I assignee(String v) { assignee = v; return this; }
        I ps(String v) { ps = v; return this; }
        I pe(String v) { pe = v; return this; }
        I est(double v) { est = v; return this; }
        I logged(double v) { logged = v; return this; }
        I remaining(double v) { remaining = v; return this; }
        I phase(String v) { phase = v; return this; }
        I logDate(String v) { logDate = v; return this; }
        I step(StepKind v) { step = v; return this; }
        I fid(String v) { fid = v; return this; }
        I created(String v) { created = v; return this; }
        I events(IssueEvent... v) { events = List.of(v); return this; }
        I summary(String v) { summary = v; return this; }
        I reporter(String v) { reporter = v; return this; }

        Issue build() {
            List<Worklog> logs = logged != 0
                    ? List.of(new Worklog(logDate != null ? logDate : ps != null ? ps : TODAY, assignee, logged)) : List.of();
            return new Issue(key, key.split("-")[0], epic, null, IssueKind.SUBTASK, summary != null ? summary : key, phase, status,
                    assignee, ps, pe, est, remaining != null ? remaining : est, created, "", fid, fid != null ? fname : null, step, logs, events, reporter);
        }
    }

    static I issue() {
        return new I();
    }

    static IssueEvent assigned(String at, String from, String to) {
        return new IssueEvent(at, EventField.ASSIGNEE, from, to);
    }

    static IssueEvent statusTo(String at, String to) {
        return new IssueEvent(at, EventField.STATUS, "W", to);
    }

    static Member member(String id, String name, Role role, String country) {
        return new Member(id, name, name, role, country, "#000");
    }
}
