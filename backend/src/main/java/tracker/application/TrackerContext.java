package tracker.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.IssueFlags;
import tracker.domain.IssueKind;
import tracker.domain.Leave;
import tracker.domain.Member;
import tracker.domain.Numbers;
import tracker.domain.Status;
import tracker.domain.WorkCalendar;

/** The loaded team context: members, calendar, the issues of the anken in scope and the flags derived from them. */
public final class TrackerContext {
    public final String today;
    public final TrackerSettings settings;
    public final Map<String, Member> members;
    public final List<Anken> anken;
    public final List<Issue> issues;
    public final List<Leave> leaves;
    public final WorkCalendar cal;
    /** The members of the scope's anken (from {@code anken_members}), distinct and sorted by id; {@link #members} holds everyone. */
    public final List<String> people;
    /** Epic to the member ids of its anken, for the epics in scope. */
    public final Map<String, Set<String>> ankenMembers;
    public final List<Function> functions = new ArrayList<>();
    public final Map<String, IssueFlags> flags = new LinkedHashMap<>();
    public final Map<String, String> owner = new LinkedHashMap<>();

    TrackerContext(String today, TrackerSettings settings, Map<String, Member> members, List<Anken> anken, List<Issue> issues,
                   List<Leave> leaves, WorkCalendar cal, List<String> people, Map<String, Set<String>> ankenMembers) {
        this.today = today;
        this.settings = settings;
        this.members = members;
        this.anken = anken;
        this.issues = issues;
        this.leaves = leaves;
        this.cal = cal;
        this.people = people;
        this.ankenMembers = ankenMembers;
    }

    /** Whether the member is in the anken of {@code epic}; false for an epic outside the scope. */
    public boolean isMember(String epic, String memberId) {
        return ankenMembers.getOrDefault(epic, Set.of()).contains(memberId);
    }

    /** "Name is not a member of Anken title", the error of a write that names someone outside the ticket's anken. */
    public String notAMember(String epic, String memberId) {
        Member m = members.get(memberId);
        return (m != null ? m.name() : memberId) + " is not a member of " + anken(epic).map(Anken::title).orElse(epic);
    }

    public List<Issue> subtasks() {
        return issues.stream().filter(i -> i.kind() == IssueKind.SUBTASK).toList();
    }

    public List<Issue> parents() {
        return issues.stream().filter(i -> i.kind() == IssueKind.PARENT).toList();
    }

    public Optional<Issue> issue(String key) {
        return issues.stream().filter(i -> i.key().equals(key)).findFirst();
    }

    public Optional<Anken> anken(String key) {
        return anken.stream().filter(a -> a.key().equals(key)).findFirst();
    }

    public String worker(Issue i) {
        return Flags.workerOf(i, owner.get(i.key()));
    }

    public TaskCard card(Issue i) {
        IssueFlags f = flags.get(i.key());
        String[] span = Flags.actualSpan(i);
        boolean closed = i.status() == Status.CLOSED;
        return new TaskCard(
                i.key(), i.summary(), i.project(), i.epicKey(), i.phase(), i.functionId(), i.functionName(),
                i.step() != null ? i.step().value() : null, i.status().value(), i.jiraStatus(), i.assigneeId(), worker(i),
                i.plannedStart(), i.plannedEnd(), i.estimateH(), Numbers.round(i.loggedH(), 2),
                closed ? 0 : Numbers.round(Math.max(0.0, i.estimateH() - i.loggedH()), 2),
                span != null ? span[0] : null, span != null ? span[1] : null,
                f.isCurrent(), f.late(), f.lateDays(), f.soon(), f.over(), f.tone(), f.heldDays(), f.rounds());
    }

    /** Derives functions (the sub-tasks of one feature, by step) and the flags of every issue. */
    void deriveFlags() {
        functions.addAll(Flags.buildFunctions(subtasks()));
        for (Function fn : functions) {
            for (int idx = 0; idx < fn.cells().size(); idx++) {
                Issue c = fn.cells().get(idx);
                if (c != null) {
                    owner.put(c.key(), fn.ownerId());
                    flags.put(c.key(), Flags.issueFlags(c, idx == fn.current(), fn.ownerId(), today, settings.thresholds));
                }
            }
        }
        for (Issue i : issues) {
            if (!flags.containsKey(i.key())) {
                owner.put(i.key(), null);
                flags.put(i.key(), Flags.issueFlags(i, i.status() != Status.CLOSED, null, today, settings.thresholds));
            }
        }
    }
}
