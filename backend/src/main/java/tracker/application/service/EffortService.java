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
import tracker.application.ports.PlanVersionRepository;
import tracker.application.ports.RemainingDayRepository;
import tracker.application.service.PlanningService.DayHead;
import tracker.domain.Analysis.EffortBar;
import tracker.domain.Analysis.Rework;
import tracker.domain.Analysis;
import tracker.domain.Baseline;
import tracker.domain.AnkenScope;
import tracker.domain.Burndown;
import tracker.domain.Capacity.PlanItem;
import tracker.domain.Capacity;
import tracker.domain.Dates;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Numbers;
import tracker.domain.PhaseGroups;
import tracker.domain.Status;
import tracker.domain.StepDef;
import tracker.domain.StepKind;
import tracker.domain.Steps;
import tracker.domain.Text;
import tracker.domain.Timesheet.TimesheetRow;
import tracker.domain.Timesheet;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;

/** Effort: timesheet (logged vs planned vs expected), one member-day in detail, estimate/rework analysis and the burndown. */
@Service
public class EffortService {
    private final TrackerContextLoader context;

    private final AllocationService allocations;
    private final RemainingDayRepository remainingDays;
    private final PlanVersionRepository planVersions;

    public EffortService(TrackerContextLoader context, AllocationService allocations, RemainingDayRepository remainingDays,
                         PlanVersionRepository planVersions) {
        this.context = context;
        this.allocations = allocations;
        this.remainingDays = remainingDays;
        this.planVersions = planVersions;
    }

    public static final int MAX_DAYS = 62;

    public record TimesheetView(String start, String end, List<DayHead> days, List<TimesheetRow> rows) {
    }

    public record DayEntryView(String key, String summary, @Nullable String functionId, @Nullable String step, double loggedH, double plannedH,
                               List<String> notes) {
    }

    public record DayDetailView(String memberId, String date, double loggedH, double plannedH, List<DayEntryView> entries) {
    }

    /** Each sub-task's planned load, shared by its step's shares (JM Review has none), handovers applied. */
    private static Capacity.Plan plan(TrackerContext ctx) {
        List<PlanItem> items = new ArrayList<>();
        for (Issue i : ctx.subtasks()) {
            items.addAll(ctx.planItems(i));
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
                            .merge(i.epicKey(), w.hours(), Numbers::sum);
                }
            }
        }
        WorkingHours wh = ctx.settings.workingHours;
        AllocationService.Counted counted = allocations.counted(scope);
        List<TimesheetRow> rows = Timesheet.timesheet(ctx.people, days, logs, plan(ctx), ctx.cal, ctx.today,
                counted.allocations(), counted.epics(), wh.timesheetToleranceH());
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

    /** The epic's sub-tasks but JM Review by phase (their parent Task's stored phase, else their own), in sync order. */
    private static Map<String, List<Issue>> subtasksByPhase(TrackerContext ctx, @Nullable String epic) {
        Map<String, String> phaseOfParent = new HashMap<>();
        ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).forEach(p -> phaseOfParent.put(p.key(), p.phase()));
        Map<String, List<Issue>> byPhase = new LinkedHashMap<>();
        for (Issue i : ctx.subtasks()) {
            if (i.epicKey().equals(epic) && !Steps.isJmReview(i)) {
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
        // JM Review is client effort: out of estimate vs logged
        List<Issue> cells = fns.stream().flatMap(f -> f.issues().stream()).filter(c -> !Steps.isJmReview(c)).toList();
        Map<String, List<Issue>> byPhase = subtasksByPhase(ctx, epic);
        Map<String, Integer> rounds = new HashMap<>();
        ctx.flags.forEach((k, f) -> rounds.put(k, f.rounds()));
        Set<String> withRows = Analysis.reworkPeople(fns, ctx.members);
        List<String> people = ctx.people.stream().filter(withRows::contains).toList();
        List<Rework> rework = Analysis.reworkByMember(fns, people, ctx.members, rounds);
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
        for (StepDef s : Steps.STEPS.stream().filter(x -> x.kind() != StepKind.JM_REVIEW).toList()) {
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
     * @param members    plan owners of the planned sub-tasks of the picked anken, phase and sub-phase who are people of the scope
     * @param subtasks   sub-tasks in the chart: Planned Start, Planned End and an estimate, after every filter
     */
    public record BurndownView(@Nullable String epicKey, String title, List<BurndownOption> ankens, List<String> phases,
                               @Nullable String phase, List<BurndownOption> tasks, @Nullable String task, List<String> members,
                               @Nullable String member, int subtasks, String today, Burndown.BurndownChart burndown) {
    }

    /**
     * A sub-task of the burndown at the end of a day: the figures the chart's point of that day sums ({@link Burndown#on}).
     *
     * @param task     its Task (sub-phase) by its name without the leading brackets
     * @param function its function's name; a sub-task outside any function: its own name
     * @param step     its step; null outside a function
     * @param picId    its plan owner, as the chart's member filter
     */
    public record BurndownTicket(String key, String taskKey, String task, String function, @Nullable String step, @Nullable String picId,
                                 double estimateH, double plannedH, @Nullable Double loggedH, double planRemainingH,
                                 @Nullable Double actualRemainingH, Burndown.@Nullable RemainSource source) {
    }

    /** The sub-tasks of the burndown at the end of {@code date}, by phase, sub-phase (by its start) and function (by its first start). */
    public record BurndownDayView(String date, String today, List<BurndownTicket> rows) {
    }

    /** The sub-tasks a burndown is drawn from, and what its filters offer. */
    private record Scope(TrackerContext ctx, @Nullable String epic, List<BurndownOption> ankens, Map<String, String> groups, List<String> phases,
                         @Nullable String phase, List<BurndownOption> tasks, @Nullable String task, Map<String, String> taskStart,
                         List<Issue> chart, List<String> members, @Nullable String who, List<Issue> picked) {
    }

    /**
     * The chart opens on the day before the start of the scope (anken, phase or sub-phase): the earliest Planned Start of its
     * sub-tasks, whoever works on them.
     *
     * @param scope     the anken the viewer sees
     * @param requested the anken, which must be in {@code scope}; null: the first tracked one with functions there
     * @param phase     a phase group; null: every phase
     * @param task      a Task (sub-phase) of the epic; null: every Task
     * @param member    the plan owner, a person of the scope; null: everyone
     */
    public BurndownView burndown(AnkenScope scope, @Nullable String requested, @Nullable String phase, @Nullable String task,
                                 @Nullable String member) {
        Scope s = scope(scope, requested, phase, task, member);
        TrackerContext ctx = s.ctx();
        List<String> keys = s.picked().stream().map(Issue::key).toList();
        List<Burndown.Item> items = items(ctx, s.picked());
        String from = s.chart().stream().map(Issue::plannedStart).min(String::compareTo).orElse(null);
        // the Baseline plan of the same scope
        String ph = s.phase();
        String tk = s.task();
        String who = s.who();
        List<Baseline.Item> baseline = Baselines.of(planVersions.baseline(s.epic())).map(b -> b.items().stream()
                .filter(x -> ph == null || ph.equals(s.groups().get(Text.orElse(x.parentKey(), ""))))
                .filter(x -> tk == null || tk.equals(x.parentKey()))
                .filter(x -> who == null || who.equals(x.ownerId())).toList()).orElse(null);
        return new BurndownView(s.epic(), titleOf(ctx, s.epic()), s.ankens(), s.phases(), ph, s.tasks(), tk, s.members(), who, keys.size(),
                ctx.today, Burndown.compute(items, ctx.cal, ctx.today, from, baseline));
    }

    /**
     * The sub-tasks of the burndown of the same filters at the end of {@code date}: their sums are the chart's point of that day.
     */
    public BurndownDayView burndownDay(AnkenScope scope, @Nullable String requested, @Nullable String phase, @Nullable String task,
                                       @Nullable String member, String date) {
        Scope s = scope(scope, requested, phase, task, member);
        TrackerContext ctx = s.ctx();
        Map<String, Burndown.ItemOn> on = new HashMap<>();
        Burndown.on(items(ctx, s.picked()), date, ctx.today).forEach(x -> on.put(x.key(), x));
        List<BurndownTicket> rows = new ArrayList<>();
        for (Issue i : ordered(ctx, s.picked(), s.groups(), s.phases(), s.taskStart())) {
            String taskKey = Text.orElse(i.parentKey(), "");
            Function fn = ctx.function(i);
            Burndown.ItemOn x = on.get(i.key());
            rows.add(new BurndownTicket(i.key(), taskKey, ctx.issue(taskKey).map(t -> PhaseGroups.taskName(t.summary())).orElse(taskKey),
                    fn != null ? fn.name() : Text.orElse(i.functionName(), i.summary()), i.step() == null ? null : i.step().value(),
                    ctx.planOwner(i), x.estimateH(), x.plannedH(), x.loggedH(), x.planRemainingH(), x.actualRemainingH(), x.source()));
        }
        return new BurndownDayView(date, ctx.today, rows);
    }

    private List<Burndown.Item> items(TrackerContext ctx, List<Issue> picked) {
        List<String> keys = picked.stream().map(Issue::key).toList();
        TrackerContext.Seen seen = new TrackerContext.Seen(remainingDays.find(keys), remainingDays.findEstimates(keys));
        return picked.stream().map(i -> ctx.burndownItem(i, seen)).toList();
    }

    private Scope scope(AnkenScope scope, @Nullable String requested, @Nullable String phase, @Nullable String task, @Nullable String member) {
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
            if (i.epicKey().equals(epic) && Text.hasText(i.plannedStart()) && Text.hasText(i.plannedEnd()) && i.estimateH() > 0
                    && !Steps.isJmReview(i)) {
                byPhase.computeIfAbsent(groups.get(Text.orElse(i.parentKey(), "")), k -> new ArrayList<>()).add(i);
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
        chart.forEach(i -> workers.add(Objects.requireNonNullElse(ctx.planOwner(i), "")));
        List<String> members = ctx.people.stream().filter(workers::contains).toList();
        String who = Text.hasText(member) ? member : null;
        List<Issue> picked = chart.stream().filter(i -> who == null || who.equals(Objects.requireNonNullElse(ctx.planOwner(i), ""))).toList();
        return new Scope(ctx, epic, ankens, groups, phases, ph, tasks, tk, start, chart, members, who, picked);
    }

    /** Sub-tasks by phase, sub-phase (by its start), function (by its first start), then their own start. */
    private static List<Issue> ordered(TrackerContext ctx, List<Issue> subtasks, Map<String, String> groups, List<String> phases,
                                       Map<String, String> taskStart) {
        Map<String, String> fnStart = new HashMap<>();
        subtasks.forEach(i -> fnStart.merge(functionKey(ctx, i), i.plannedStart(), (a, b) -> a.compareTo(b) <= 0 ? a : b));
        java.util.function.Function<Issue, String> taskOf = i -> Text.orElse(i.parentKey(), "");
        return subtasks.stream().sorted(Comparator
                .comparingInt((Issue i) -> phases.indexOf(groups.get(taskOf.apply(i))))
                .thenComparing(i -> taskStart.getOrDefault(taskOf.apply(i), ""))
                .thenComparing(taskOf)
                .thenComparing(i -> fnStart.get(functionKey(ctx, i)))
                .thenComparing(i -> functionKey(ctx, i))
                .thenComparing(Issue::plannedStart)
                .thenComparing(Issue::key)).toList();
    }

    /** The function a sub-task is a step of; a sub-task outside any function is its own. */
    private static String functionKey(TrackerContext ctx, Issue i) {
        Function fn = ctx.function(i);
        return fn != null ? "fn:" + fn.parentKey() + ":" + fn.id() : "issue:" + i.key();
    }
}
