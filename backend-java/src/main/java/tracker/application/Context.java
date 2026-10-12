package tracker.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tracker.application.ports.UnitOfWork;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.IssueFlags;
import tracker.domain.IssueKind;
import tracker.domain.Leave;
import tracker.domain.Member;
import tracker.domain.Num;
import tracker.domain.Status;
import tracker.domain.Thresholds;
import tracker.domain.WorkCalendar;

/** The loaded team context: members, calendar, the tracked issues and the flags derived from them. */
public final class Context {
    public final String today;
    public final TrackerSettings settings;
    public final Map<String, Member> members;
    public final List<Anken> anken;
    public final List<Issue> issues;
    public final List<Leave> leaves;
    public final WorkCalendar cal;
    public List<Function> functions = new ArrayList<>();
    public final Map<String, IssueFlags> flags = new LinkedHashMap<>();
    public final Map<String, String> owner = new LinkedHashMap<>();

    Context(String today, TrackerSettings settings, Map<String, Member> members, List<Anken> anken, List<Issue> issues,
            List<Leave> leaves, WorkCalendar cal) {
        this.today = today;
        this.settings = settings;
        this.members = members;
        this.anken = anken;
        this.issues = issues;
        this.leaves = leaves;
        this.cal = cal;
    }

    public List<Issue> subtasks() {
        return issues.stream().filter(i -> i.kind() == IssueKind.SUBTASK).toList();
    }

    public List<Issue> parents() {
        return issues.stream().filter(i -> i.kind() == IssueKind.PARENT).toList();
    }

    public Issue get(String key) {
        return issues.stream().filter(i -> i.key().equals(key)).findFirst().orElse(null);
    }

    public String worker(Issue i) {
        return Flags.workerOf(i, owner.get(i.key()));
    }

    public TaskCard card(Issue i) {
        IssueFlags f = flags.get(i.key());
        String[] span = Flags.actualSpan(i, today);
        boolean closed = i.status() == Status.CLOSED;
        return new TaskCard(
                i.key(), i.summary(), i.project(), i.epicKey(), i.phase(), i.functionId(), i.functionName(),
                i.step() != null ? i.step().value() : null, i.status().value(), i.jiraStatus(), i.assigneeId(), worker(i),
                i.plannedStart(), i.plannedEnd(), i.estimateH(), Num.round(i.loggedH(), 2),
                closed ? 0 : Num.round(Math.max(0.0, i.estimateH() - i.loggedH()), 2),
                span != null ? span[0] : null, span != null ? span[1] : null,
                f.isCurrent(), f.late(), f.lateDays(), f.soon(), f.over(), f.tone(), f.heldDays(), f.rounds());
    }

    public static Map<String, Member> membersById(List<Member> members) {
        Map<String, Member> out = new LinkedHashMap<>();
        members.forEach(m -> out.put(m.id(), m));
        return out;
    }

    public static WorkCalendar makeCalendar(UnitOfWork uow, TrackerSettings settings, Map<String, Member> members, List<Leave> leaves) {
        return new WorkCalendar(settings.workingHours.days(), uow.calendar().extraDays(), uow.calendar().holidays(), members, leaves);
    }

    /** Loads members, calendar and the issues of the tracked epics (or {@code epics}), and derives their flags. */
    public static Context load(UnitOfWork uow, String today, List<String> epics) {
        TrackerSettings settings = uow.settings().load();
        Map<String, Member> members = membersById(uow.members().list());
        List<Leave> leaves = uow.calendar().leaves();
        WorkCalendar cal = makeCalendar(uow, settings, members, leaves);
        List<String> tracked = epics != null ? epics : settings.jira.epics;
        List<Anken> anken = uow.anken().list().stream().filter(a -> tracked.contains(a.key())).toList();
        Context ctx = new Context(today, settings, members, anken, uow.issues().list(tracked), leaves, cal);
        ctx.functions = Flags.buildFunctions(ctx.subtasks());
        Thresholds t = settings.thresholds;
        for (Function fn : ctx.functions) {
            for (int idx = 0; idx < fn.cells().size(); idx++) {
                Issue c = fn.cells().get(idx);
                if (c != null) {
                    ctx.owner.put(c.key(), fn.ownerId());
                    ctx.flags.put(c.key(), Flags.issueFlags(c, idx == fn.current(), fn.ownerId(), today, t));
                }
            }
        }
        for (Issue i : ctx.issues) {
            if (!ctx.flags.containsKey(i.key())) {
                ctx.owner.put(i.key(), null);
                ctx.flags.put(i.key(), Flags.issueFlags(i, i.status() != Status.CLOSED, null, today, t));
            }
        }
        return ctx;
    }

    public static Context load(UnitOfWork uow, String today) {
        return load(uow, today, null);
    }
}
