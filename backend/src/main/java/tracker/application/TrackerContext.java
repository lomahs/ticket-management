package tracker.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.Capacity;
import tracker.domain.Effort;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.IssueFlags;
import tracker.domain.IssueKind;
import tracker.domain.IssueSchedule;
import tracker.domain.Leave;
import tracker.domain.Member;
import tracker.domain.Numbers;
import tracker.domain.ScheduleRules;
import tracker.domain.Status;
import tracker.domain.StepKind;
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
    /** Start, finish and effort labels of every issue; {@link #flags} derive late and tone from them. */
    public final Map<String, IssueSchedule> schedule = new LinkedHashMap<>();
    /** The legacy worker identity (the Create step's assignee) by key: Planning and Capacity only (spec §4). */
    public final Map<String, String> owner = new LinkedHashMap<>();
    private final Map<String, String> planOwner = new LinkedHashMap<>();
    private final Map<String, Function> functionOf = new LinkedHashMap<>();

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

    /** The legacy worker: Planning and Capacity only (spec §4). Every other number reads {@link #planOwner}. */
    public String worker(Issue i) {
        return Flags.workerOf(i, owner.get(i.key()));
    }

    /** Who the plan of {@code i} belongs to by the role of its step ({@link Effort#planOwner}); null on JM Review. */
    public @Nullable String planOwner(Issue i) {
        return planOwner.get(i.key());
    }

    /** The function {@code i} is a step of; null for any other issue. */
    public @Nullable Function function(Issue i) {
        return functionOf.get(i.key());
    }

    /** The planned load of {@code i}: its plan owner's, and after a BA handover the new BA's remaining estimate. */
    public Capacity.@Nullable PlanItem planItem(Issue i) {
        Function fn = function(i);
        boolean baStep = fn != null && i.step() != StepKind.BAL_REVIEW && i.step() != StepKind.JM_REVIEW;
        return Effort.planItem(i, planOwner(i), baStep ? fn.handoverDay(members) : null);
    }

    public TaskCard card(Issue i) {
        IssueFlags f = flags.get(i.key());
        String[] span = Flags.actualSpan(i);
        boolean closed = i.status() == Status.CLOSED;
        return new TaskCard(
                i.key(), i.summary(), i.project(), i.epicKey(), i.phase(), i.functionId(), i.functionName(),
                i.step() != null ? i.step().value() : null, i.status().value(), i.jiraStatus(), i.assigneeId(), worker(i), planOwner(i),
                i.plannedStart(), i.plannedEnd(), i.estimateH(), Numbers.round(i.loggedH(), 2),
                closed ? 0 : Numbers.round(Math.max(0.0, i.estimateH() - i.loggedH()), 2),
                span != null ? span[0] : null, span != null ? span[1] : null,
                f.isCurrent(), f.late(), f.lateDays(), f.soon(), f.over(), f.tone(), f.heldDays(), f.rounds(),
                schedule.get(i.key()));
    }

    /** Derives functions (the sub-tasks of one feature, by step), then the schedule labels and flags of every issue. */
    void deriveFlags() {
        functions.addAll(Flags.buildFunctions(subtasks()));
        for (Function fn : functions) {
            for (int idx = 0; idx < fn.cells().size(); idx++) {
                Issue c = fn.cells().get(idx);
                if (c != null) {
                    owner.put(c.key(), fn.ownerId());
                    planOwner.put(c.key(), Effort.planOwner(fn, c, members));
                    functionOf.put(c.key(), fn);
                    flags.put(c.key(), Flags.issueFlags(c, idx == fn.current(), members, today, settings.thresholds, schedule(c)));
                }
            }
        }
        for (Issue i : issues) {
            if (!flags.containsKey(i.key())) {
                owner.put(i.key(), null);
                planOwner.put(i.key(), Effort.planOwner(null, i, members));
                flags.put(i.key(), Flags.issueFlags(i, i.status() != Status.CLOSED, members, today, settings.thresholds, schedule(i)));
            }
        }
    }

    /** Computes and keeps the schedule labels of {@code i}; its plan owner must be known already. */
    private IssueSchedule schedule(Issue i) {
        IssueSchedule s = ScheduleRules.of(i, planOwner(i), today, cal, settings.thresholds.effortDiffH());
        schedule.put(i.key(), s);
        return s;
    }
}
