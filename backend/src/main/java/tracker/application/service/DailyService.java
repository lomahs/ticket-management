package tracker.application.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.NotFound;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.DailySnapshotRepository;
import tracker.application.ports.RemainingDayRepository;
import tracker.application.ports.TicketNoteRepository;
import tracker.application.settings.SettingsStore;
import tracker.domain.AnkenScope;
import tracker.domain.Burndown;
import tracker.domain.Burndown.BurndownChart;
import tracker.domain.Dates;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.IssueFlags;
import tracker.domain.Member;
import tracker.domain.Overview;
import tracker.domain.Overview.PhaseRollup;
import tracker.domain.PhaseGroups;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.Steps;
import tracker.domain.TaskTemplate;
import tracker.domain.Text;
import tracker.domain.WorkStructure;

/**
 * Daily Overview: one anken at a glance for the daily meeting with the client (JM). Today is computed live; each day can be
 * captured, and a past day shows what was captured then.
 */
@Service
public class DailyService {
    /** A closed QnA, Bug or WorkRequest stays listed for this many calendar days after it was closed. */
    static final int CLOSED_DAYS = 7;

    private final TrackerContextLoader context;
    private final DailySnapshotRepository snapshots;
    private final RemainingDayRepository remainingDays;
    private final TicketNoteRepository notes;
    private final SettingsStore settings;
    private final Clock clock;

    public DailyService(TrackerContextLoader context, DailySnapshotRepository snapshots, RemainingDayRepository remainingDays,
                        TicketNoteRepository notes, SettingsStore settings, Clock clock) {
        this.context = context;
        this.snapshots = snapshots;
        this.remainingDays = remainingDays;
        this.notes = notes;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * One step of a function.
     *
     * @param rounds review rounds of a fix step (0 on other steps)
     * @param withJm whether the step's assignee is a JM: the ticket is with the client
     */
    public record DailyStep(String key, String step, String status, @Nullable String assigneeId, @Nullable String plannedStart,
                            @Nullable String plannedEnd, boolean late, int lateDays, int rounds, boolean withJm) {
    }

    /** @param steps one slot per step of the function (3 or 5), null when that sub-task does not exist */
    public record DailyFunction(String id, String name, @Nullable String baId, List<@Nullable DailyStep> steps) {
    }

    /** A single ticket: a sub-task outside the 5-step workflow. */
    public record DailyTicket(String key, String name, @Nullable String assigneeId, String status, @Nullable String plannedStart,
                              @Nullable String plannedEnd, boolean late, int lateDays) {
    }

    /**
     * A Task (sub-phase) of a phase group with its functions and single tickets, rolled up like its group.
     *
     * @param state     done | current | not_started
     * @param late      sub-tasks whose finish is overdue
     * @param unmatched sub-tasks that are neither: their Summary does not match the name rule
     */
    public record DailySubPhase(String key, String name, String state, int pct, int closed, int total, int late,
                                List<DailyFunction> functions, List<DailyTicket> tickets, int unmatched) {
    }

    /**
     * @param state     done | current | not_started; a group without sub-tasks is left out
     * @param late      sub-tasks whose finish is overdue
     * @param subPhases the group's Tasks that have sub-tasks
     */
    public record DailyGroup(String name, String state, int pct, int closed, int total, int late, List<DailySubPhase> subPhases) {
    }

    /**
     * A sub-task of a Task typed QnA, Bug or WorkRequest.
     *
     * @param type     qna | bug | work_request
     * @param withJm   whether the assignee is a JM: the ticket is with the client, else with the team
     * @param heldDays working days with the current assignee
     * @param closedOn the day it was closed, when closed
     */
    public record DailyTyped(String type, String key, String summary, @Nullable String assigneeId, boolean withJm, String status,
                             String created, int heldDays, @Nullable String closedOn, String note) {
    }

    /**
     * @param pct   logged / (logged + remaining) over the sub-tasks
     * @param diffH actual remain − plan remain in hours: positive is behind the plan
     */
    public record DailyProgress(int pct, double planRemainH, double actualRemainH, double diffH) {
        static DailyProgress of(PhaseRollup r) {
            return new DailyProgress(r.pct(), r.schedule().planRemainH(), r.schedule().actualRemainH(), r.schedule().diffH());
        }
    }

    /**
     * @param phase    a phase group; null: the whole anken
     * @param progress the progress and effort vs plan of the same sub-tasks; null in a snapshot captured before it was kept
     */
    public record DailyBurndown(@Nullable String phase, BurndownChart chart, @Nullable DailyProgress progress) {
    }

    /**
     * What a snapshot keeps: the anken on {@code date}.
     *
     * @param pct  logged / (logged + remaining) over every sub-task of the anken
     * @param diffH actual remain − plan remain in hours: positive is behind the plan
     */
    public record DailyContent(String epic, String title, String date, int pct, double planRemainH, double actualRemainH, double diffH,
                               List<DailyBurndown> burndowns, List<DailyGroup> groups, List<DailyTyped> typed) {
    }

    /** @param capturedBy a member id, or "auto" for the scheduled capture */
    public record SnapshotOption(String date, String capturedAt, String capturedBy) {
    }

    /**
     * @param live        whether {@link #content} is computed now (today) rather than read from a snapshot
     * @param capturedAt  when the shown day was captured (ISO local date-time with offset); null when it was not
     * @param autoCapture "HH:mm" of the daily capture, or "off"
     * @param snapshots   the anken's captured days, newest first
     * @param dailyPhases the phases the anken's Daily setting lists; always the current setting, also for a past day
     */
    public record DailyView(DailyContent content, boolean live, @Nullable String capturedAt, @Nullable String capturedBy, String today,
                            String autoCapture, List<SnapshotOption> snapshots, List<String> dailyPhases) {
    }

    /** @param date a captured day; null or today: live */
    public DailyView view(AnkenScope scope, String epic, @Nullable String date) {
        TrackerContext ctx = context.load(scope);
        require(ctx, epic);
        List<SnapshotOption> list = snapshots.list(epic).stream().map(s -> new SnapshotOption(s.date(), s.capturedAt(), s.capturedBy())).toList();
        String day = Text.hasText(date) ? date : ctx.today;
        SnapshotOption captured = list.stream().filter(s -> s.date().equals(day)).findFirst().orElse(null);
        String auto = settings.load().dailyCapture;
        List<String> daily = ctx.settings.structureOf(epic).daily();
        if (day.equals(ctx.today)) {
            return new DailyView(content(ctx, epic), true, captured != null ? captured.capturedAt() : null,
                    captured != null ? captured.capturedBy() : null, ctx.today, auto, list, daily);
        }
        DailyContent saved = captured == null ? null : snapshots.find(epic, day, DailyContent.class).orElse(null);
        if (saved == null) {
            throw new NotFound("No Daily Overview of " + epic + " was captured on " + day, "date");
        }
        return new DailyView(saved, false, captured.capturedAt(), captured.capturedBy(), ctx.today, auto, list, daily);
    }

    /**
     * Captures today's Daily Overview of {@code epic}, replacing an earlier capture of today, and the remaining estimate of each
     * of its sub-tasks (0 once Closed), which the burndowns read back for this day.
     */
    public DailyView capture(AnkenScope scope, String epic, String by) {
        TrackerContext ctx = context.load(scope);
        require(ctx, epic);
        Map<String, Double> remaining = new LinkedHashMap<>();
        ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic))
                .forEach(i -> remaining.put(i.key(), i.status() == Status.CLOSED ? 0 : i.remainingH()));
        remainingDays.save(ctx.today, remaining);
        snapshots.save(epic, ctx.today, now(), by, content(ctx, epic));
        return view(scope, epic, null);
    }

    /** The tracked anken without a capture today, which the scheduled capture would take. */
    public List<String> uncaptured() {
        String today = context.today();
        return settings.load().jira.epics.stream().filter(e -> snapshots.list(e).stream().noneMatch(s -> s.date().equals(today))).toList();
    }

    /** Sets the note of a sub-task the viewer sees; a blank note removes it. */
    public String saveNote(AnkenScope scope, String key, String note, String by) {
        TrackerContext ctx = context.load(scope);
        ctx.issue(key).orElseThrow(() -> new NotFound("Ticket " + key + " was not found", "key"));
        String n = note.strip();
        notes.save(key, n, by, now());
        return n;
    }

    private String now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static void require(TrackerContext ctx, String epic) {
        ctx.anken(epic).orElseThrow(() -> new NotFound("Anken " + epic + " is not tracked", "anken"));
    }

    private DailyContent content(TrackerContext ctx, String epic) {
        WorkStructure st = ctx.settings.structureOf(epic);
        List<Issue> parents = ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList();
        List<Issue> subs = ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic)).toList();
        Map<String, Map<String, Double>> seen = remainingDays.find(subs.stream().map(Issue::key).toList());
        Map<String, String> typeOf = new HashMap<>();
        parents.forEach(p -> typeOf.put(p.key(), st.typeOfTask(p.summary())));
        Map<String, List<Issue>> subsOf = new HashMap<>();
        subs.forEach(i -> subsOf.computeIfAbsent(Text.orElse(i.parentKey(), ""), k -> new ArrayList<>()).add(i));

        // phase groups of the Tasks that are not typed
        Map<String, String> groupOf = PhaseGroups.of(parents, st);
        Map<String, List<Issue>> tasksByGroup = new LinkedHashMap<>();
        Map<String, List<Issue>> subsByGroup = new LinkedHashMap<>();
        for (Issue p : parents) {
            if (typeOf.get(p.key()).isEmpty()) {
                String g = groupOf.get(p.key());
                tasksByGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(p);
                subsByGroup.computeIfAbsent(g, k -> new ArrayList<>()).addAll(subsOf.getOrDefault(p.key(), List.of()));
            }
        }
        List<DailyGroup> groups = new ArrayList<>();
        List<DailyBurndown> burndowns = new ArrayList<>();
        for (String g : PhaseGroups.ordered(subsByGroup, st.phases())) {
            PhaseRollup r = Overview.phaseRollup(g, subsByGroup.get(g), ctx.schedule);
            Overview.Schedule state = r.schedule().state();
            if (state == Overview.Schedule.UNKNOWN) {
                continue;
            }
            List<DailySubPhase> subPhases = tasksByGroup.get(g).stream()
                    .filter(t -> subsOf.containsKey(t.key()))
                    .sorted(Comparator.comparing((Issue t) -> earliestStart(subsOf.get(t.key()))).thenComparing(Issue::key))
                    .map(t -> subPhase(ctx, t, subsOf.get(t.key()))).toList();
            groups.add(new DailyGroup(g, stateOf(state), r.pct(), r.closed(), r.total(), r.schedule().overdue(), subPhases));
            burndowns.add(new DailyBurndown(g, burndown(ctx, subsByGroup.get(g), seen), DailyProgress.of(r)));
        }

        Map<String, String> noted = notes.find(subs.stream().map(Issue::key).toList());
        List<DailyTyped> typed = typed(ctx, parents, typeOf, subsOf).stream()
                .map(t -> new DailyTyped(t.type(), t.key(), t.summary(), t.assigneeId(), t.withJm(), t.status(), t.created(), t.heldDays(),
                        t.closedOn(), noted.getOrDefault(t.key(), ""))).toList();
        PhaseRollup all = Overview.phaseRollup(epic, subs, ctx.schedule);
        burndowns.addFirst(new DailyBurndown(null, burndown(ctx, subs, seen), DailyProgress.of(all)));
        String title = ctx.anken(epic).map(tracker.domain.Anken::title).orElse(epic);
        return new DailyContent(epic, title, ctx.today, all.pct(), all.schedule().planRemainH(), all.schedule().actualRemainH(),
                all.schedule().diffH(), burndowns, groups, typed);
    }

    private static String stateOf(Overview.Schedule s) {
        return switch (s) {
            case DONE -> "done";
            case NOT_STARTED, UNKNOWN -> "not_started";
            default -> "current";
        };
    }

    private static String earliestStart(List<Issue> subs) {
        return subs.stream().map(Issue::plannedStart).filter(Text::hasText).min(String::compareTo).orElse("9999");
    }

    private static boolean withJm(TrackerContext ctx, @Nullable String memberId) {
        Member m = memberId != null ? ctx.members.get(memberId) : null;
        return m != null && m.role() == Role.JM;
    }

    private static DailySubPhase subPhase(TrackerContext ctx, Issue task, List<Issue> subs) {
        List<DailyFunction> fns = ctx.functions.stream().filter(f -> task.key().equals(f.parentKey()))
                .map(f -> function(ctx, f)).toList();
        List<DailyTicket> tickets = subs.stream().filter(Issue::single)
                .sorted(Comparator.comparing((Issue i) -> Text.orElse(i.plannedStart(), "9999")).thenComparing(Issue::key))
                .map(i -> {
                    IssueFlags f = ctx.flags.get(i.key());
                    return new DailyTicket(i.key(), i.functionName(), i.assigneeId(), i.status().value(), i.plannedStart(), i.plannedEnd(),
                            f.late(), f.lateDays());
                }).toList();
        int unmatched = (int) subs.stream().filter(i -> i.functionId() == null && !i.single()).count();
        PhaseRollup r = Overview.phaseRollup(task.key(), subs, ctx.schedule);
        return new DailySubPhase(task.key(), PhaseGroups.taskName(task.summary()), stateOf(r.schedule().state()), r.pct(), r.closed(),
                r.total(), r.schedule().overdue(), fns, tickets, unmatched);
    }

    private static DailyFunction function(TrackerContext ctx, Function f) {
        List<DailyStep> steps = new ArrayList<>();
        for (Issue c : f.steps()) {
            if (c == null) {
                steps.add(null);
                continue;
            }
            IssueFlags fl = ctx.flags.get(c.key());
            steps.add(new DailyStep(c.key(), c.step().value(), c.status().value(), c.assigneeId(), c.plannedStart(), c.plannedEnd(),
                    fl.late(), fl.lateDays(), fl.rounds(), withJm(ctx, c.assigneeId())));
        }
        return new DailyFunction(f.id(), f.name(), f.baId(ctx.members), steps);
    }

    private static List<DailyTyped> typed(TrackerContext ctx, List<Issue> parents, Map<String, String> typeOf, Map<String, List<Issue>> subsOf) {
        String since = Dates.addDays(ctx.today, -CLOSED_DAYS);
        List<DailyTyped> out = new ArrayList<>();
        for (Issue p : parents) {
            String type = typeOf.get(p.key());
            if (type.isEmpty()) {
                continue;
            }
            for (Issue i : subsOf.getOrDefault(p.key(), List.of())) {
                String closedOn = null;
                if (i.status() == Status.CLOSED) {
                    String[] span = Flags.actualSpan(i);
                    closedOn = span != null && span[1] != null ? span[1] : i.created();
                    if (closedOn.compareTo(since) < 0) {
                        continue;
                    }
                }
                out.add(new DailyTyped(type, i.key(), i.summary(), i.assigneeId(), withJm(ctx, i.assigneeId()), i.status().value(),
                        i.created(), ctx.flags.get(i.key()).heldDays(), closedOn, ""));
            }
        }
        out.sort(Comparator.comparingInt((DailyTyped t) -> TaskTemplate.TYPES.indexOf(t.type()))
                .thenComparing(t -> t.closedOn() != null)
                .thenComparing(DailyTyped::created).thenComparing(DailyTyped::key));
        return out;
    }

    /** @param seen key → day → the remaining estimate a capture saw */
    private static BurndownChart burndown(TrackerContext ctx, List<Issue> subs, Map<String, Map<String, Double>> seen) {
        List<Burndown.Item> items = subs.stream()
                .filter(i -> Text.hasText(i.plannedStart()) && Text.hasText(i.plannedEnd()) && i.estimateH() > 0 && !Steps.isJmReview(i))
                .map(i -> ctx.burndownItem(i, seen.getOrDefault(i.key(), Map.of()))).toList();
        return Burndown.compute(items, ctx.cal, ctx.today, null);
    }
}
