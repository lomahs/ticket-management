package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.service.PlanningService.DayHead;
import tracker.domain.Analysis.EffortBar;
import tracker.domain.Analysis.Rework;
import tracker.domain.Analysis;
import tracker.domain.AnkenScope;
import tracker.domain.Burndown;
import tracker.domain.Capacity.PlanItem;
import tracker.domain.Capacity;
import tracker.domain.Dates;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Numbers;
import tracker.domain.PhaseGroups;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepDef;
import tracker.domain.Steps;
import tracker.domain.Text;
import tracker.application.ports.AllocationRepository;
import tracker.domain.Allocations;
import tracker.domain.Timesheet.TimesheetRow;
import tracker.domain.Timesheet;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;

/** Effort: timesheet (logged vs planned vs expected), one member-day in detail, estimate/rework analysis and the burndown. */
@Service
public class EffortService {
    private final TrackerContextLoader context;

    private final AllocationRepository allocations;

    public EffortService(TrackerContextLoader context, AllocationRepository allocations) {
        this.context = context;
        this.allocations = allocations;
    }

    public static final int MAX_DAYS = 62;

    public record TimesheetView(String start, String end, List<DayHead> days, List<TimesheetRow> rows) {
    }

    public record DayEntryView(String key, String summary, @Nullable String functionId, @Nullable String step, double loggedH, double plannedH,
                               List<String> notes) {
    }

    public record DayDetailView(String memberId, String date, double loggedH, double plannedH, List<DayEntryView> entries) {
    }

    private static Capacity.Plan plan(TrackerContext ctx) {
        List<PlanItem> items = new ArrayList<>();
        for (Issue i : ctx.subtasks()) {
            if (Text.hasText(i.plannedStart()) && Text.hasText(i.plannedEnd())) {
                String w = ctx.worker(i);
                if (Text.hasText(w)) {
                    items.add(new PlanItem(i.key(), w, i.plannedStart(), i.plannedEnd(), i.estimateH()));
                }
            }
        }
        return Capacity.dailyPlan(items, ctx.cal);
    }

    /** One row per person of the scope, from the worklogs of its issues. */
    public TimesheetView timesheet(AnkenScope scope, String start, String end) {
        if (end.compareTo(start) < 0 || Dates.dayDiff(start, end) >= MAX_DAYS) {
            throw new Invalid("Choose a range of 1 to " + MAX_DAYS + " days", "to");
        }
        TrackerContext ctx = context.load(scope);
        List<String> days = Dates.daysBetween(start, end).stream().filter(ctx.cal::visible).toList();
        Map<String, Map<String, Double>> logs = new HashMap<>();
        for (Issue i : ctx.issues) {
            for (Worklog w : i.worklogs()) {
                if (start.compareTo(w.date()) <= 0 && w.date().compareTo(end) <= 0) {
                    logs.computeIfAbsent(Timesheet.logKey(w.memberId(), w.date()), k -> new HashMap<>())
                            .merge(i.epicKey(), w.hours(), Double::sum);
                }
            }
        }
        WorkingHours wh = ctx.settings.workingHours;
        Set<String> scopeEpics = new HashSet<>(ctx.settings.jira.epics);
        scopeEpics.retainAll(scope.epics());
        List<TimesheetRow> rows = Timesheet.timesheet(ctx.people, days, logs, plan(ctx), ctx.cal, ctx.today,
                new Allocations(allocations.findAll()), scopeEpics, wh.timesheetToleranceH());
        return new TimesheetView(start, end,
                days.stream().map(x -> new DayHead(x, x.equals(ctx.today), ctx.cal.extraDays().get(x))).toList(), rows);
    }

    /** A person of the scope; only the worklogs on its issues. */
    public DayDetailView dayDetail(AnkenScope scope, String memberId, String date) {
        TrackerContext ctx = context.load(scope);
        if (!ctx.people.contains(memberId)) {
            throw new NotFound("Member " + memberId + " was not found", "member");
        }
        Map<String, List<Worklog>> byKey = new LinkedHashMap<>();
        for (Issue i : ctx.issues) {
            List<Worklog> logs = i.worklogs().stream()
                    .filter(w -> Objects.equals(w.memberId(), memberId) && w.date().equals(date)).toList();
            if (!logs.isEmpty()) {
                byKey.put(i.key(), logs);
            }
        }
        Timesheet.DayDetail dd = Timesheet.dayDetail(byKey, plan(ctx), memberId, date);
        Map<String, Issue> issues = new HashMap<>();
        ctx.issues.forEach(i -> issues.put(i.key(), i));
        List<DayEntryView> entries = new ArrayList<>();
        for (Timesheet.DayEntry e : dd.entries()) {
            Issue i = issues.get(e.key());
            entries.add(new DayEntryView(e.key(), i != null ? i.summary() : e.key(), i != null ? i.functionId() : null,
                    i != null && i.step() != null ? i.step().value() : null, e.loggedH(), e.plannedH(), e.notes()));
        }
        return new DayDetailView(memberId, date, dd.loggedH(), dd.plannedH(), entries);
    }

    /**
     * @param loggedH     on started function sub-tasks
     * @param reworkRate  fix hours ÷ create hours
     * @param fixTickets  fix sub-tasks with at least one review round
     */
    public record EffortKpis(double loggedH, double estimateH, @Nullable Double reworkRate, @Nullable Double avgRounds, int fixTickets,
                             int overSubtasks) {
    }

    public record EffortAnalysis(@Nullable String epicKey, String title, EffortKpis kpis, List<EffortBar> byPhase, List<EffortBar> byStep,
                                 List<Rework> rework) {
    }

    /** @param requested the anken asked for; null: the first tracked one with functions */
    private static @Nullable String epicOf(TrackerContext ctx, @Nullable String requested) {
        List<String> withFns = ctx.settings.jira.epics.stream()
                .filter(k -> ctx.functions.stream().anyMatch(f -> f.epicKey().equals(k))).toList();
        String epic = Text.hasText(requested) ? requested : (withFns.isEmpty() ? null : withFns.get(0));
        if (Text.hasText(epic) && ctx.anken.stream().noneMatch(a -> a.key().equals(epic))) {
            throw new NotFound("Anken " + epic + " is not tracked", "anken");
        }
        return epic;
    }

    private static String titleOf(TrackerContext ctx, @Nullable String epic) {
        return ctx.anken.stream().filter(a -> a.key().equals(epic)).map(tracker.domain.Anken::title).findFirst().orElse("");
    }

    /** The epic's sub-tasks by phase (their parent Task's stored phase, else their own), in sync order. */
    private static Map<String, List<Issue>> subtasksByPhase(TrackerContext ctx, @Nullable String epic) {
        Map<String, String> phaseOfParent = new HashMap<>();
        ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).forEach(p -> phaseOfParent.put(p.key(), p.phase()));
        Map<String, List<Issue>> byPhase = new LinkedHashMap<>();
        for (Issue i : ctx.subtasks()) {
            if (i.epicKey().equals(epic)) {
                byPhase.computeIfAbsent(phaseOfParent.getOrDefault(i.parentKey() == null ? "" : i.parentKey(), i.phase()),
                        k -> new ArrayList<>()).add(i);
            }
        }
        return byPhase;
    }

    /**
     * @param scope     the anken the viewer sees
     * @param requested the anken to analyse, which must be in {@code scope}; null: the first tracked one with functions there
     */
    public EffortAnalysis analysis(AnkenScope scope, @Nullable String requested) {
        TrackerContext ctx = context.load(scope);
        String epic = epicOf(ctx, requested);
        String title = titleOf(ctx, epic);
        List<Function> fns = ctx.functions.stream().filter(f -> f.epicKey().equals(epic)).toList();
        List<Issue> cells = fns.stream().flatMap(f -> f.issues().stream()).toList();
        Map<String, List<Issue>> byPhase = subtasksByPhase(ctx, epic);
        Map<String, Integer> rounds = new HashMap<>();
        ctx.flags.forEach((k, f) -> rounds.put(k, f.rounds()));
        List<String> bas = ctx.people.stream().filter(id -> ctx.members.containsKey(id) && ctx.members.get(id).role() == Role.BA
                && fns.stream().anyMatch(f -> Objects.equals(f.ownerId(), id))).toList();
        List<Rework> rework = Analysis.reworkByMember(fns, bas, rounds);
        List<Issue> started = cells.stream().filter(c -> c.status() != Status.NYS).toList();
        List<Issue> fixes = cells.stream().filter(c -> Steps.isFixStep(c) && rounds.getOrDefault(c.key(), 0) > 0).toList();
        double createH = rework.stream().mapToDouble(Rework::createH).sum();
        double fixH = rework.stream().mapToDouble(Rework::fixH).sum();
        EffortKpis kpis = new EffortKpis(
                Numbers.round(started.stream().mapToDouble(Issue::loggedH).sum(), 2),
                Numbers.round(started.stream().mapToDouble(Issue::estimateH).sum(), 2),
                createH != 0 ? Numbers.round(fixH / createH, 4) : null,
                fixes.isEmpty() ? null : Numbers.round(fixes.stream().mapToInt(c -> rounds.get(c.key())).sum() / (double) fixes.size(), 2),
                fixes.size(), (int) started.stream().filter(c -> ctx.flags.get(c.key()).over()).count());
        List<Analysis.Group> phaseGroups = ctx.settings.structureOf(epic).orderedPhases(byPhase.keySet()).stream()
                .map(p -> new Analysis.Group(p, byPhase.get(p))).toList();
        List<Analysis.Group> stepGroups = new ArrayList<>();
        for (StepDef s : Steps.STEPS) {
            stepGroups.add(new Analysis.Group(s.label(), cells.stream().filter(c -> c.step() == s.kind()).toList()));
        }
        return new EffortAnalysis(epic, title, kpis, Analysis.estimateVsLogged(phaseGroups), Analysis.estimateVsLogged(stepGroups),
                rework);
    }

    /** A choice in a burndown filter: an anken, or a Task (sub-phase) by its name without the leading brackets. */
    public record BurndownOption(String key, String label) {
    }

    /**
     * @param ankens     every anken in scope, in Settings order
     * @param phases     phase groups of the anken's planned sub-tasks, in Settings → Structure order
     * @param tasks      Tasks (sub-phases) of {@code phase} with planned sub-tasks, by planned start; empty without a phase
     * @param members    workers of the planned sub-tasks of the picked anken, phase and sub-phase who are people of the scope
     * @param subtasks   sub-tasks in the chart: Planned Start, Planned End and an estimate, after every filter
     */
    public record BurndownView(@Nullable String epicKey, String title, List<BurndownOption> ankens, List<String> phases,
                               @Nullable String phase, List<BurndownOption> tasks, @Nullable String task, List<String> members,
                               @Nullable String member, int subtasks, String today, Burndown.BurndownChart burndown) {
    }

    /**
     * The chart opens on the day before the start of the scope (anken, phase or sub-phase): the earliest Planned Start of its
     * sub-tasks, whoever works on them.
     *
     * @param scope     the anken the viewer sees
     * @param requested the anken, which must be in {@code scope}; null: the first tracked one with functions there
     * @param phase     a phase group; null: every phase
     * @param task      a Task (sub-phase) of the epic; null: every Task
     * @param member    the worker, a person of the scope; null: everyone
     */
    public BurndownView burndown(AnkenScope scope, @Nullable String requested, @Nullable String phase, @Nullable String task,
                                 @Nullable String member) {
        TrackerContext ctx = context.load(scope);
        if (Text.hasText(member) && !ctx.people.contains(member)) {
            throw new NotFound("Member " + member + " was not found", "member");
        }
        String epic = epicOf(ctx, requested);
        Map<String, Integer> order = new HashMap<>();
        for (int n = 0; n < ctx.settings.jira.epics.size(); n++) {
            order.put(ctx.settings.jira.epics.get(n), n);
        }
        List<BurndownOption> ankens = ctx.anken.stream().sorted(Comparator.comparingInt(a -> order.getOrDefault(a.key(), 99)))
                .map(a -> new BurndownOption(a.key(), a.title())).toList();
        List<Issue> parents = ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList();
        Map<String, String> groups = PhaseGroups.of(parents, ctx.settings.structureOf(epic));
        Map<String, List<Issue>> byPhase = new LinkedHashMap<>();
        Map<String, String> start = new HashMap<>();
        for (Issue i : ctx.subtasks()) {
            if (i.epicKey().equals(epic) && Text.hasText(i.plannedStart()) && Text.hasText(i.plannedEnd()) && i.estimateH() > 0) {
                byPhase.computeIfAbsent(groups.getOrDefault(Text.orElse(i.parentKey(), ""), PhaseGroups.OTHER), k -> new ArrayList<>()).add(i);
                if (i.parentKey() != null) {
                    start.merge(i.parentKey(), i.plannedStart(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
                }
            }
        }
        List<String> phases = PhaseGroups.ordered(byPhase, ctx.settings.structureOf(epic).phases());
        String ph = Text.hasText(phase) ? phase : null;
        String tk = Text.hasText(task) ? task : null;
        List<BurndownOption> tasks = ph == null ? List.of() : parents.stream()
                .filter(p -> ph.equals(groups.get(p.key())) && start.containsKey(p.key()))
                .sorted(Comparator.comparing((Issue p) -> start.get(p.key())).thenComparing(Issue::key))
                .map(p -> new BurndownOption(p.key(), PhaseGroups.taskName(p.summary()))).toList();
        List<Issue> chart = new ArrayList<>();
        byPhase.forEach((p, issues) -> issues.forEach(i -> {
            if ((ph == null || ph.equals(p)) && (tk == null || tk.equals(i.parentKey()))) {
                chart.add(i);
            }
        }));
        Set<String> workers = new HashSet<>();
        chart.forEach(i -> workers.add(Objects.requireNonNullElse(ctx.worker(i), "")));
        List<String> members = ctx.people.stream().filter(workers::contains).toList();
        String who = Text.hasText(member) ? member : null;
        List<Burndown.Item> items = new ArrayList<>();
        for (Issue i : chart) {
            String w = Objects.requireNonNullElse(ctx.worker(i), "");
            if (who == null || who.equals(w)) {
                items.add(new Burndown.Item(i.key(), w, i.plannedStart(), i.plannedEnd(), i.estimateH(), i.worklogs()));
            }
        }
        String from = chart.stream().map(Issue::plannedStart).min(String::compareTo).orElse(null);
        return new BurndownView(epic, titleOf(ctx, epic), ankens, phases, ph, tasks, tk, members, who, items.size(), ctx.today,
                Burndown.compute(items, ctx.cal, ctx.today, from));
    }
}
