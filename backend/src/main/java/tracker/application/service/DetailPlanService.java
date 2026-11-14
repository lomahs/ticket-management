package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.ports.OnboardingRepository;
import tracker.application.ports.PlanCellRepository;
import tracker.application.ports.PlanDayRepository;
import tracker.application.ports.PlanVersionRepository;
import tracker.domain.Allocations;
import tracker.domain.AutoAllocator;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.AutoPicks;
import tracker.domain.Capacity;
import tracker.domain.Dates;
import tracker.domain.DetailMatrix;
import tracker.domain.DetailScheduler;
import tracker.domain.Effort;
import tracker.domain.DetailScheduler.DayOverrun;
import tracker.domain.DetailScheduler.Overrun;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.MasterSchedule;
import tracker.domain.Numbers;
import tracker.domain.PlanRows;
import tracker.domain.StepEdit;
import tracker.domain.Onboarding;
import tracker.domain.PhaseGroups;
import tracker.domain.Role;
import tracker.domain.ScheduleVersion;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.StepWeights;
import tracker.domain.Text;
import tracker.domain.Viewer;
import tracker.application.service.AnkenService.ParentOption;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.TicketService.NewTicket;

/** The detail plan: who does each row and the dates that follow, checked against the master schedule; then the push to Jira. */
@Service
public class DetailPlanService {
    private final TrackerContextLoader context;
    private final EstimationRepository estimation;
    private final MasterScheduleRepository versions;
    private final AllocationService allocations;
    private final TicketService tickets;
    private final OnboardingRepository onboarding;
    private final EstimationService estimations;
    private final PlanDayRepository planDays;
    private final PlanCellRepository cells;
    private final PlanVersionRepository planVersions;
    private final Clock clock;

    public DetailPlanService(TrackerContextLoader context, EstimationRepository estimation, MasterScheduleRepository versions,
                             AllocationService allocations, TicketService tickets, OnboardingRepository onboarding,
                             EstimationService estimations, PlanDayRepository planDays, PlanCellRepository cells,
                             PlanVersionRepository planVersions, Clock clock) {
        this.context = context;
        this.estimation = estimation;
        this.versions = versions;
        this.allocations = allocations;
        this.tickets = tickets;
        this.onboarding = onboarding;
        this.estimations = estimations;
        this.planDays = planDays;
        this.cells = cells;
        this.planVersions = planVersions;
        this.clock = clock;
    }

    /** @param baId the BA of a function, or the one member of a row without steps */
    public record RowAssign(long rowId, @Nullable String baId, @Nullable String balId, @Nullable String jmId) {
    }

    /** @param order the unpushed rows' ids in their new order */
    public record DraftIn(List<Long> order, List<RowAssign> assigns) {
    }

    /** @param auto filled by Auto allocate, not typed */
    public record DayHours(String day, double hours, boolean auto) {
    }

    /**
     * One step of a function, a ticket, or a sub-task in Jira.
     *
     * @param ref       the step's ref ("12:create", or "12" for a ticket) of a row, the issue key of a sub-task in Jira
     * @param rowId     the estimation row; null for a sub-task in Jira
     * @param issueKey  the sub-task in Jira; null for a row not pushed yet
     * @param phase     the row's phase; the phase group of a sub-task's Task
     * @param step      the step's value ("create", "balr", …); null for a ticket
     * @param summary   the sub-task's Summary (by Settings › Name rules for a row)
     * @param picId     who it is (or will be) assigned to: a pending PIC first
     * @param reporterId the BAL on a Create step of a row; a sub-task's reporter in Jira
     * @param totalH    a row's step effort; Spent + Remaining for a sub-task in Jira (Spent alone once Closed)
     * @param spentH    logged hours (Jira only; 0 otherwise)
     * @param plannedH  a row's hours in its cells; a sub-task's Spent + its cells from today on
     * @param notYetH   Total − Planned
     * @param start     the first day with hours; {@code end} the last; null without cells
     * @param cells     the hours planned per day, by day
     * @param logged    logged hours by day (Jira only)
     * @param pending   a Jira row with changes not pushed yet
     * @param outOfOrder starts before the previous step of its function ends
     * @param edited    its effort was set by hand instead of Create × its weight
     */
    // issue_key named outright: the OpenAPI resolver leaves "issueKey" (a name starting "is") unconverted
    public record MatrixLine(String ref, @Nullable Long rowId, @JsonProperty("issue_key") @Nullable String issueKey, String phase, String function,
                             @Nullable String step, String summary, @Nullable String picId, @Nullable String reporterId,
                             double totalH, double spentH, double plannedH, double notYetH, @Nullable String start, @Nullable String end,
                             List<DayHours> cells, List<DayHours> logged, boolean closed, boolean pending, boolean outOfOrder,
                             boolean removed, boolean edited) {
    }

    /** @param daysLate calendar days from the master schedule's End to the plan's end; positive = later */
    public record PhaseCheck(String phase, String masterEnd, @Nullable String planEnd, int daysLate) {
    }

    /** Hours a day a member is allocated to an anken. */
    public record AnkenAllocation(String epicKey, String title, double hoursPerDay) {
    }

    /**
     * What a member over budget in a phase is allocated to on the phase's Start: this anken first, then every other tracked
     * anken with hours.
     */
    public record OverrunAllocation(String memberId, String phase, String date, List<AnkenAllocation> ankens) {
    }

    /**
     * What a member has on a day.
     *
     * @param key   the Jira key of a sub-task already in Jira, the anken's epic key for work on another anken in the viewer's
     *              scope; null for a sub-task of this plan and for another anken outside the scope
     * @param title the sub-task's Summary, or the other anken's title
     * @param other work on another anken (its open sub-tasks there, shared by the step weights)
     */
    public record DayItem(@Nullable String key, String title, double hours, boolean other) {
    }

    /**
     * A member's day in the plan's window.
     *
     * @param plannedH   hours planned on this anken: this plan and the open sub-tasks already in Jira
     * @param allocatedH the allocation on the anken that day
     * @param off        why the member does not work that day (weekend, holiday, leave); null on a working day
     * @param otherH     hours planned on the other anken the member works on
     * @param items      what makes up {@code plannedH} and {@code otherH}
     */
    public record DayLoad(String memberId, String date, double plannedH, double allocatedH, @Nullable String off, double otherH,
                          List<DayItem> items) {
    }

    /**
     * @param window       the day columns: working days from the earliest of the master schedule's start and the first cell to the
     *                     latest of its end and the last cell
     * @param lines        a line per step of the unpushed rows (removed ones included) in row order, then a line per sub-task of
     *                     the anken in Jira, by phase and function
     * @param days         the members with hours in the plan, day by day over {@code window}
     * @param stalePhases  phases whose BA effort changed since the master schedule was saved (its End is out of date)
     * @param problems     what keeps the plan from being computed by Auto allocate, and from being pushed
     * @param pushBlockers why Push is refused, besides {@code problems}: a line not fully planned, planned over its total, or out of
     *                     step order
     * @param allocated    one per entry of {@code overBudget}, in the same order
     * @param byHand       the slots of the unpushed rows picked by hand (not by Auto allocate), for the rows with any
     * @param lastVersionAt when the last version of the plan was saved; null when none was
     */
    public record DetailPlanView(List<EstimationRow> rows, List<EstimationService.TaskOption> tasks, List<String> window,
                                 List<MatrixLine> lines, List<Overrun> overBudget, List<OverrunAllocation> allocated,
                                 List<DayOverrun> overDay, List<PhaseCheck> checks, List<DayLoad> days, List<String> stalePhases,
                                 List<String> problems, List<String> pushBlockers, List<HandPicks> byHand, @Nullable String lastVersionAt) {
    }

    /** Which slots of a row hold someone picked by hand. */
    public record HandPicks(long rowId, boolean ba, boolean bal, boolean jm) {
    }

    /**
     * A change to one sub-task of the plan; the fields replace what the step had.
     *
     * @param step    the step's value; null or blank for a ticket
     * @param effortH null: Create × the step's weight (for Create and a ticket: the row's effort, which it then sets)
     * @param start   with {@code end}: the dates set by hand; both null: computed
     */
    public record StepIn(long rowId, @Nullable String step, @Nullable Double effortH, @Nullable String start, @Nullable String end,
                         boolean removed) {
    }

    /** A function (3 or 5 steps) or a ticket ({@code steps} null) added to the estimation from the plan. */
    public record NewRowIn(String taskKey, String functionName, @Nullable Integer steps, double effortH) {
    }

    /** The computed plan of an anken's unpushed rows, with what it needs to be pushed. */
    record Computed(TrackerContext ctx, @Nullable ScheduleVersion schedule, List<EstimationRow> rows, Map<Long, List<PlanRows.RowStep>> stepsOf,
                    Map<String, String> names, List<Placed> placed, List<DetailScheduler.Part> parts, StepWeights weights, DetailPlanView view) {
    }

    /** The plan as it shows. */
    public DetailPlanView view(AnkenScope scope, String epic) {
        return compute(scope, epic).view();
    }

    /** Saves the order of the unpushed rows and their people, picked by hand; returns the plan that follows. */
    public DetailPlanView saveDraft(AnkenScope scope, String epic, DraftIn draft) {
        saveDraft(scope, epic, draft, true);
        return view(scope, epic);
    }

    /**
     * @param byHand the people come from the PIM: a slot that changes is no longer Auto allocate's pick; else from Auto
     *               allocate, which remembers who it put in each slot it filled
     */
    private void saveDraft(AnkenScope scope, String epic, DraftIn draft, boolean byHand) {
        EstimationService.load(context, scope, epic);
        List<EstimationRow> all = estimation.findByEpic(epic);
        List<EstimationRow> open = all.stream().filter(r -> !r.pushed()).toList();
        Map<Long, RowAssign> assign = new HashMap<>();
        draft.assigns().forEach(a -> assign.put(a.rowId(), a));
        Map<Long, AutoPicks> auto = autoPicks(epic);
        List<EstimationRow> ordered = new ArrayList<>();
        draft.order().forEach(id -> open.stream().filter(r -> r.id() == id).findFirst().ifPresent(ordered::add));
        open.stream().filter(r -> !ordered.contains(r)).forEach(ordered::add);
        int position = all.size() - open.size();
        List<EstimationRow> next = new ArrayList<>();
        List<AutoPicks> picks = new ArrayList<>();
        for (EstimationRow r : ordered) {
            RowAssign a = assign.get(r.id());
            String ba = a != null ? blank(a.baId()) : r.baId();
            String bal = a != null ? blank(a.balId()) : r.balId();
            String jm = a != null ? blank(a.jmId()) : r.jmId();
            next.add(new EstimationRow(r.id(), epic, position++, r.phase(), r.subphase(), r.functionName(), r.steps(), r.effortH(), ba, bal, jm,
                    r.taskKey(), null));
            AutoPicks was = auto.getOrDefault(r.id(), AutoPicks.none(r.id()));
            AutoPicks now = new AutoPicks(r.id(), pick(was.baId(), r.baId(), ba, byHand), pick(was.balId(), r.balId(), bal, byHand),
                    pick(was.jmId(), r.jmId(), jm, byHand));
            if (!now.equals(was)) {
                picks.add(now);
            }
        }
        estimation.save(epic, next);
        picks.forEach(estimation::saveAutoPicks);
    }

    /** Auto allocate's pick of a slot after it changed from {@code before} to {@code after}: kept while the slot stays. */
    private static @Nullable String pick(@Nullable String auto, @Nullable String before, @Nullable String after, boolean byHand) {
        if (Objects.equals(before, after)) {
            return auto;
        }
        return byHand ? null : after;
    }

    private Map<Long, AutoPicks> autoPicks(String epic) {
        Map<Long, AutoPicks> out = new HashMap<>();
        estimation.autoPicks(epic).forEach(p -> out.put(p.rowId(), p));
        return out;
    }

    /**
     * Empties the slots of the unpushed rows. {@code keepInput}: only those still holding Auto allocate's pick; the people
     * picked by hand, the dates and efforts set by hand and the removed steps stay. Else every slot, and every step edit goes
     * too: the plan is the estimation's again.
     */
    @Transactional
    public DetailPlanView clear(AnkenScope scope, String epic, boolean keepInput) {
        EstimationService.load(context, scope, epic);
        List<EstimationRow> open = estimation.findByEpic(epic).stream().filter(r -> !r.pushed()).toList();
        Map<Long, AutoPicks> auto = autoPicks(epic);
        List<EstimationRow> next = new ArrayList<>();
        for (EstimationRow r : open) {
            AutoPicks p = auto.getOrDefault(r.id(), AutoPicks.none(r.id()));
            next.add(new EstimationRow(r.id(), epic, r.position(), r.phase(), r.subphase(), r.functionName(), r.steps(), r.effortH(),
                    keep(r.baId(), p.baId(), keepInput), keep(r.balId(), p.balId(), keepInput), keep(r.jmId(), p.jmId(), keepInput),
                    r.taskKey(), null));
            estimation.saveAutoPicks(AutoPicks.none(r.id()));
        }
        estimation.save(epic, next);
        if (!keepInput) {
            estimation.deleteUnpushedStepEdits(epic);
        }
        return view(scope, epic);
    }

    private static @Nullable String keep(@Nullable String picked, @Nullable String auto, boolean keepInput) {
        return keepInput && AutoPicks.byHand(picked, auto) ? picked : null;
    }

    /**
     * Fills the empty BA / BAL / JM slots of the unpushed rows ({@link AutoAllocator}, docs/auto-allocation.md): BAs, BALs and
     * JMs from the phase's team in the master schedule. People already picked stay; the draft is saved. Refused with the first
     * input problem left, the people staying.
     */
    public DetailPlanView autoAllocate(AnkenScope scope, String epic) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        List<EstimationRow> open = estimation.findByEpic(epic).stream().filter(r -> !r.pushed()).toList();
        List<ScheduleVersion> saved = versions.findByEpic(epic);
        if (saved.isEmpty()) {
            throw new Invalid("Save a master schedule first", "plan");
        }
        Map<String, AutoAllocator.Phase> phases = new LinkedHashMap<>();
        phases(saved.getLast()).forEach((name, p) -> phases.put(name,
                new AutoAllocator.Phase(p.start(), p.end(), p.memberIds().stream().filter(id -> ctx.isMember(epic, id)).toList())));
        Map<String, Role> roles = new HashMap<>();
        ctx.members.values().stream().filter(m -> m.role() != null).forEach(m -> roles.put(m.id(), m.role()));
        Allocations a = allocations.counted(scope).allocations();
        StepWeights w = ctx.settings.stepWeightsOf(epic);
        List<StepEdit> edits = estimation.stepEdits(epic);
        AutoAllocator.Input in = new AutoAllocator.Input(epic,
                open.stream().map(r -> new AutoAllocator.Row(r.id(), r.phase(), r.steps(), r.effortH(), r.baId(), r.balId(), r.jmId(),
                        PlanRows.steps(r.id(), r.steps(), r.effortH(), edits, w))).toList(),
                phases, roles, loads(ctx, epic, Set.of()), givenBefore(ctx, epic, Set.of()),
                AutoAllocator.cached(DetailScheduler.hours(epic, a, ctx.cal)), ctx.cal, w, ctx.settings.workingHours.hoursPerDay());
        List<RowAssign> assigns = AutoAllocator.allocate(in).stream().map(p -> new RowAssign(p.rowId(), p.baId(), p.balId(), p.jmId())).toList();
        saveDraft(scope, epic, new DraftIn(open.stream().map(EstimationRow::id).toList(), assigns), false);
        List<String> problems = basis(scope, epic).problems();
        if (!problems.isEmpty()) {
            throw new Invalid(problems.getFirst(), "plan");
        }
        return view(scope, epic);
    }

    private static @Nullable String blank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** What the plan of an anken's unpushed rows is made from: its rows, their steps, the master schedule and what is missing. */
    private record Basis(TrackerContext ctx, List<EstimationRow> all, List<EstimationRow> rows, List<StepEdit> edits, StepWeights w,
                         Map<Long, List<PlanRows.RowStep>> stepsOf, @Nullable ScheduleVersion schedule,
                         List<EstimationService.TaskOption> tasks, List<String> problems) {
    }

    private Basis basis(AnkenScope scope, String epic) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        List<EstimationRow> all = estimation.findByEpic(epic);
        List<EstimationRow> rows = all.stream().filter(r -> !r.pushed()).toList();
        List<StepEdit> edits = estimation.stepEdits(epic);
        StepWeights w = ctx.settings.stepWeightsOf(epic);
        Map<Long, List<PlanRows.RowStep>> stepsOf = new LinkedHashMap<>();
        rows.forEach(r -> stepsOf.put(r.id(), PlanRows.steps(r.id(), r.steps(), r.effortH(), edits, w)));
        List<ScheduleVersion> saved = versions.findByEpic(epic);
        ScheduleVersion schedule = saved.isEmpty() ? null : saved.getLast();
        List<EstimationService.TaskOption> tasks = EstimationService.tasks(ctx, epic);
        return new Basis(ctx, all, rows, edits, w, stepsOf, schedule, tasks, problems(ctx, epic, schedule, rows, stepsOf, w, tasks));
    }

    /**
     * The plan as it shows: the matrix lines (the unpushed rows' steps, then the anken's sub-tasks in Jira) with their cells; effort
     * per day, overruns and the phase checks follow from the cells, with the allocations as they are now.
     */
    Computed compute(AnkenScope scope, String epic) {
        Basis b = basis(scope, epic);
        TrackerContext ctx = b.ctx();
        List<EstimationRow> rows = b.rows();
        StepWeights w = b.w();
        Map<Long, List<PlanRows.RowStep>> stepsOf = b.stepsOf();
        ScheduleVersion schedule = b.schedule();
        Allocations a = allocations.counted(scope).allocations();
        Map<String, PhaseRow> phases = phases(schedule);
        Map<String, String> names = summaries(ctx, epic, onboarding.find(epic).map(Onboarding::ankenPrefix).orElse(null), rows, stepsOf);
        DetailScheduler.Hours hours = DetailScheduler.hours(epic, a, ctx.cal);
        PlanLines.Built built = PlanLines.build(ctx, epic, rows, stepsOf, names, w, cells.rowCells(epic), cells.jiraCells(epic),
                cells.jiraEdits(epic));
        List<MatrixLine> lines = built.lines();
        List<Placed> placed = built.placed();
        List<DetailScheduler.Part> parts = built.parts();
        Map<String, Map<String, Double>> ledger = ledger(parts);
        List<DayOverrun> overDay = overDay(ledger, parts, hours);
        List<Overrun> over = DetailScheduler.overBudget(placed.stream().filter(p -> phases.containsKey(p.phase())).toList(),
                givenBefore(ctx, epic, Set.of()), phases, epic, a, ctx.cal);
        List<OverrunAllocation> allocated = over.stream()
                .map(o -> allocated(ctx, epic, a, o.memberId(), o.phase(), phases.get(o.phase()).start())).toList();
        List<String> window = window(ctx, schedule, lines);
        List<PhaseCheck> checks = schedule == null ? List.of() : checks(schedule, lines);
        List<DayLoad> days = days(ctx, epic, a, ledger, parts, lines, window);
        List<String> stale = new ArrayList<>();
        if (schedule != null) {
            Map<String, MasterSchedule.PhaseWork> work = MasterSchedule.work(b.all(), b.edits(), w, ctx.settings.workingHours.hoursPerDay());
            for (PhaseRow p : schedule.phases()) {
                MasterSchedule.PhaseWork pw = work.get(p.phase());
                if (pw != null && Math.abs(pw.effortH() - p.totalEffortH()) > 0.01) {
                    stale.add(p.phase());
                }
            }
        }
        Map<Long, AutoPicks> auto = autoPicks(epic);
        List<HandPicks> byHand = new ArrayList<>();
        for (EstimationRow r : rows) {
            AutoPicks p = auto.getOrDefault(r.id(), AutoPicks.none(r.id()));
            HandPicks h = new HandPicks(r.id(), AutoPicks.byHand(r.baId(), p.baId()), AutoPicks.byHand(r.balId(), p.balId()),
                    AutoPicks.byHand(r.jmId(), p.jmId()));
            if (h.ba() || h.bal() || h.jm()) {
                byHand.add(h);
            }
        }
        DetailPlanView view = new DetailPlanView(b.all(), b.tasks(), window, lines, over, allocated, overDay, checks, days, stale,
                b.problems(), pushBlockers(lines), byHand, planVersions.lastSavedAt(epic));
        return new Computed(ctx, schedule, rows, stepsOf, names, placed, parts, w, view);
    }

    /**
     * Working days from the earliest of the master schedule's first Start and the first cell of any line to the latest of its last
     * End and the last cell; none without either.
     */
    private static List<String> window(TrackerContext ctx, @Nullable ScheduleVersion schedule, List<MatrixLine> lines) {
        String from = null;
        String to = null;
        for (PhaseRow p : schedule == null ? List.<PhaseRow>of() : schedule.phases()) {
            from = min(from, p.start());
            to = max(to, p.end());
        }
        for (MatrixLine l : lines) {
            for (DayHours c : l.cells()) {
                from = min(from, c.day());
                to = max(to, c.day());
            }
        }
        return DetailMatrix.window(from, to, ctx.cal::visible);
    }

    private static @Nullable String min(@Nullable String a, String b) {
        return a == null || b.compareTo(a) < 0 ? b : a;
    }

    private static @Nullable String max(@Nullable String a, String b) {
        return a == null || b.compareTo(a) > 0 ? b : a;
    }

    /** Why Push is refused, line by line (removed and Closed lines aside): hours not planned yet, planned over the total, step order. */
    private static List<String> pushBlockers(List<MatrixLine> lines) {
        List<String> out = new ArrayList<>();
        for (MatrixLine l : lines) {
            if (l.removed() || l.closed()) {
                continue;
            }
            if (l.notYetH() > 0.01) {
                out.add(l.summary() + ": " + Numbers.format(l.notYetH()) + "h not planned yet");
            } else if (l.notYetH() < -0.01) {
                out.add(l.summary() + ": " + Numbers.format(-l.notYetH()) + "h planned over the total");
            }
            if (l.outOfOrder()) {
                out.add(l.summary() + ": starts before the step before it ends");
            }
        }
        return out;
    }

    /** Hours per member and day of the plan's parts. */
    private static Map<String, Map<String, Double>> ledger(List<DetailScheduler.Part> parts) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        parts.forEach(p -> out.computeIfAbsent(p.memberId(), k -> new TreeMap<>()).merge(p.date(), p.hours(), Numbers::sum));
        return out;
    }

    /** The days the plan gives a member hours on where the ledger has more than the member's hours on the anken. */
    private static List<DayOverrun> overDay(Map<String, Map<String, Double>> ledger, List<DetailScheduler.Part> parts, DetailScheduler.Hours hours) {
        Map<String, Set<String>> touched = new TreeMap<>();
        parts.forEach(p -> touched.computeIfAbsent(p.memberId(), k -> new TreeSet<>()).add(p.date()));
        List<DayOverrun> out = new ArrayList<>();
        touched.forEach((m, days) -> days.forEach(d -> {
            double h = ledger.get(m).get(d);
            double cap = hours.on(m, d);
            if (h > cap + 1e-9) {
                out.add(new DayOverrun(m, d, Numbers.round(h, 2), cap));
            }
        }));
        return out;
    }

    /**
     * The members of the anken with hours in the plan's window, each day of it, with what makes up their hours: the matrix lines'
     * cells (the unpushed rows' and the anken's sub-tasks in Jira) and their open sub-tasks on the other tracked anken (by anken).
     */
    private List<DayLoad> days(TrackerContext ctx, String epic, Allocations a, Map<String, Map<String, Double>> ledger,
                               List<DetailScheduler.Part> parts, List<MatrixLine> lines, List<String> window) {
        if (window.isEmpty()) {
            return List.of();
        }
        Map<String, MatrixLine> lineOf = new HashMap<>();
        lines.forEach(l -> lineOf.put(l.ref(), l));
        Map<String, Map<String, List<DayItem>>> mine = new HashMap<>();
        for (DetailScheduler.Part p : parts) {
            MatrixLine l = lineOf.get(p.ref());
            mine.computeIfAbsent(p.memberId(), k -> new HashMap<>()).computeIfAbsent(p.date(), k -> new ArrayList<>())
                    .add(new DayItem(l.issueKey(), l.summary(), Numbers.round(p.hours(), 2), false));
        }
        // the other anken: every tracked one, whatever the viewer's scope (only their titles and hours are shown)
        TrackerContext all = context.load(new AnkenScope(Set.copyOf(ctx.settings.jira.epics)));
        Map<String, String> epicOf = new HashMap<>();
        List<Capacity.PlanItem> elsewhere = new ArrayList<>();
        for (Issue i : all.subtasks()) {
            if (!i.epicKey().equals(epic) && i.status() != Status.CLOSED) {
                epicOf.put(i.key(), i.epicKey());
                elsewhere.addAll(all.planItems(i));
            }
        }
        Capacity.Plan other = Capacity.dailyPlan(elsewhere, all.cal);
        List<DayLoad> out = new ArrayList<>();
        for (String m : new TreeSet<>(ledger.keySet())) {
            Map<String, Double> day = ledger.get(m);
            if (!ctx.isMember(epic, m) || window.stream().noneMatch(d -> day.getOrDefault(d, 0.0) > 1e-9)) {
                continue;
            }
            for (String d : window) {
                List<DayItem> items = new ArrayList<>(mine.getOrDefault(m, Map.of()).getOrDefault(d, List.of()));
                Map<String, Double> byEpic = new TreeMap<>();
                Capacity.DayPlan op = other.day(m, d);
                if (op != null) {
                    op.byKey.forEach((key, h) -> byEpic.merge(epicOf.get(key), h, Numbers::sum));
                }
                // an anken outside the viewer's scope shows by its title only
                byEpic.forEach((e, h) -> items.add(new DayItem(ctx.anken(e).isPresent() ? e : null, all.anken(e).map(Anken::title).orElse(e),
                        Numbers.round(h, 2), true)));
                String off = ctx.cal.off(d, m);
                out.add(new DayLoad(m, d, Numbers.round(day.getOrDefault(d, 0.0), 2), Objects.requireNonNullElse(a.hoursOn(m, epic, d), 0.0),
                        Text.hasText(off) ? off : null, Numbers.round(byEpic.values().stream().mapToDouble(Double::doubleValue).sum(), 2), items));
            }
        }
        return out;
    }

    /** Ref → the sub-task's Summary by Settings › Name rules, under the row's Task. */
    private static Map<String, String> summaries(TrackerContext ctx, String epic, @Nullable String onboardedPrefix, List<EstimationRow> rows,
                                                 Map<Long, List<PlanRows.RowStep>> stepsOf) {
        Map<String, ParentOption> parents = new HashMap<>();
        AnkenService.parentOptions(ctx, epic).forEach(p -> parents.put(p.key(), p));
        Map<String, String> out = new HashMap<>();
        for (EstimationRow r : rows) {
            ParentOption p = r.taskKey() == null ? null : parents.get(r.taskKey());
            if (p == null) {
                continue;
            }
            // the sub-phase prefix of the names: the Task's, else its phase, else its phase group, else the row's phase
            String phase = Text.hasText(p.phase()) ? p.phase() : !PhaseGroups.OTHER.equals(p.group()) ? p.group() : r.phase();
            String prefix = Text.hasText(p.prefix()) ? p.prefix() : phase;
            String ankenPrefix = AnkenService.ankenPrefix(ctx, epic, onboardedPrefix, p.summary());
            for (PlanRows.RowStep st : stepsOf.get(r.id())) {
                out.put(st.ref(), AnkenService.subtaskSummary(ctx, epic, ankenPrefix, prefix, r.functionName(), st.kind()));
            }
        }
        return out;
    }

    private static OverrunAllocation allocated(TrackerContext ctx, String epic, Allocations a, String memberId, String phase, String date) {
        List<AnkenAllocation> out = new ArrayList<>();
        out.add(new AnkenAllocation(epic, title(ctx, epic), Objects.requireNonNullElse(a.hoursOn(memberId, epic, date), 0.0)));
        for (String e : ctx.settings.jira.epics) {
            Double h = e.equals(epic) ? null : a.hoursOn(memberId, e, date);
            if (h != null && h > 0) {
                out.add(new AnkenAllocation(e, title(ctx, e), h));
            }
        }
        return new OverrunAllocation(memberId, phase, date, out);
    }

    private static String title(TrackerContext ctx, String epic) {
        return ctx.anken(epic).map(Anken::title).orElse("");
    }

    static long rowOf(String ref) {
        int i = ref.indexOf(':');
        return Long.parseLong(i < 0 ? ref : ref.substring(0, i));
    }

    static Map<String, PhaseRow> phases(@Nullable ScheduleVersion v) {
        Map<String, PhaseRow> out = new LinkedHashMap<>();
        if (v != null) {
            v.phases().forEach(p -> out.put(p.phase(), p));
        }
        return out;
    }

    private static List<String> problems(TrackerContext ctx, String epic, @Nullable ScheduleVersion schedule, List<EstimationRow> rows,
                                         Map<Long, List<PlanRows.RowStep>> stepsOf, StepWeights w, List<EstimationService.TaskOption> tasks) {
        List<String> out = new ArrayList<>();
        if (rows.isEmpty()) {
            return out;
        }
        if (schedule == null) {
            out.add("Save a master schedule first");
        }
        Map<String, PhaseRow> phases = phases(schedule);
        Set<String> missing = new LinkedHashSet<>();
        for (int n = 0; n < rows.size(); n++) {
            EstimationRow r = rows.get(n);
            String row = "Row " + (n + 1) + " (" + r.functionName() + ")";
            if (schedule != null && !phases.containsKey(r.phase())) {
                missing.add(r.phase() + " is not in the master schedule");
            }
            if (!Text.hasText(r.taskKey())) {
                out.add(row + ": choose a Task");
            } else if (tasks.stream().noneMatch(t -> t.key().equals(r.taskKey()))) {
                out.add(row + ": " + r.taskKey() + " is not a Task of " + epic);
            }
            if (r.steps() == null) {
                person(ctx, epic, out, row, r.baId(), "a member");
                continue;
            }
            person(ctx, epic, out, row, r.baId(), "a BA");
            if (PlanRows.needs(stepsOf.get(r.id()), Role.BAL, w)) {
                person(ctx, epic, out, row, r.balId(), "a BAL");
            }
            if (PlanRows.needs(stepsOf.get(r.id()), Role.JM, w)) {
                person(ctx, epic, out, row, r.jmId(), "a JM");
            }
        }
        out.addAll(missing);
        return out;
    }

    private static void person(TrackerContext ctx, String epic, List<String> out, String row, @Nullable String id, String what) {
        if (!Text.hasText(id)) {
            out.add(row + ": pick " + what);
        } else if (!ctx.isMember(epic, id)) {
            out.add(row + ": " + ctx.notAMember(epic, id));
        }
    }

    /** Task key → phase group of the epic's Tasks. */
    static Map<String, String> groupOfTask(TrackerContext ctx, String epic) {
        return PhaseGroups.of(ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList(), ctx.settings.structureOf(epic));
    }

    /** The anken's sub-tasks in Jira that are not Closed, other than {@code except}. */
    static List<Issue> openSubtasks(TrackerContext ctx, String epic, Set<String> except) {
        return ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic) && i.status() != Status.CLOSED && !except.contains(i.key())).toList();
    }

    /** Hours per member and day of the anken's planned, not Closed sub-tasks (JM Review aside), spread over their days. */
    static Map<String, Map<String, Double>> loads(TrackerContext ctx, String epic, Set<String> except) {
        Capacity.Plan plan = loadPlan(ctx, epic, except);
        Map<String, Map<String, Double>> out = new HashMap<>();
        for (String m : ctx.people) {
            plan.member(m).forEach((d, dp) -> out.computeIfAbsent(m, k -> new TreeMap<>()).put(d, dp.total));
        }
        return out;
    }

    /** {@link #loads}, sub-task by sub-task. */
    static Capacity.Plan loadPlan(TrackerContext ctx, String epic, Set<String> except) {
        List<Capacity.PlanItem> items = new ArrayList<>();
        for (Issue i : openSubtasks(ctx, epic, except)) {
            for (Effort.PlanShare sh : ctx.planShares(i)) {
                if (Text.hasText(i.plannedStart()) && Text.hasText(i.plannedEnd())) {
                    items.add(new Capacity.PlanItem(i.key(), sh.memberId(), i.plannedStart(), i.plannedEnd(), i.estimateH() * sh.fraction()));
                }
            }
        }
        return Capacity.dailyPlan(items, ctx.cal);
    }

    /**
     * "member|phase" → estimate a member of the anken already has in the phase in Jira (JM Review aside). Closed sub-tasks are
     * done and use no budget; sub-tasks of people outside the anken use nobody's budget there.
     */
    static Map<String, Double> givenBefore(TrackerContext ctx, String epic, Set<String> except) {
        Map<String, String> groups = groupOfTask(ctx, epic);
        Map<String, Double> out = new HashMap<>();
        for (Issue i : openSubtasks(ctx, epic, except)) {
            for (Effort.PlanShare sh : ctx.planShares(i)) {
                if (ctx.isMember(epic, sh.memberId())) {
                    out.merge(sh.memberId() + "|" + groups.getOrDefault(Text.orElse(i.parentKey(), ""), PhaseGroups.OTHER),
                            i.estimateH() * sh.fraction(), Numbers::sum);
                }
            }
        }
        return out;
    }

    /** Each master phase against the latest End of its lines (removed and Closed ones aside). */
    static List<PhaseCheck> checks(ScheduleVersion schedule, List<MatrixLine> lines) {
        List<PhaseCheck> out = new ArrayList<>();
        for (PhaseRow p : schedule.phases()) {
            String end = lines.stream().filter(l -> !l.closed() && !l.removed() && l.end() != null && l.phase().equals(p.phase()))
                    .map(MatrixLine::end).max(String::compareTo).orElse(null);
            out.add(new PhaseCheck(p.phase(), p.end(), end, end == null ? 0 : Dates.dayDiff(p.end(), end)));
        }
        return out;
    }

    /** The review of the push: every sub-task, with the request that creates it. */
    public BatchPreview pushPreview(AnkenScope scope, String epic) {
        Computed c = pushable(scope, epic);
        return tickets.previewBatch(scope, epic, List.of(), newTickets(c, labels(epic)));
    }

    /**
     * Creates the sub-tasks of the unpushed rows under their Tasks; a row is marked pushed once all its sub-tasks exist, also
     * when Jira refuses a request half-way. Each sub-task created keeps the plan's hours per day, which its plan remain counts
     * down by.
     */
    public BatchResult push(Viewer actor, AnkenScope scope, String epic) {
        Computed c = pushable(scope, epic);
        List<NewTicket> creates = newTickets(c, labels(epic));
        return tickets.applyBatch(actor, scope, epic, List.of(), creates, created -> {
            created.forEach((ref, key) -> planDays.save(key, daysOf(line(c, ref))));
            List<Long> done = new ArrayList<>();
            for (EstimationRow r : c.rows()) { // a row whose steps were all removed has nothing to create and is done too
                List<String> refs = creates.stream().map(NewTicket::ref).filter(ref -> rowOf(ref) == r.id()).toList();
                if (created.keySet().containsAll(refs)) {
                    done.add(r.id());
                }
            }
            if (!done.isEmpty()) {
                estimation.markPushed(done, Instant.now(clock).toString());
            }
        });
    }

    /** The matrix line of {@code ref}. */
    private static MatrixLine line(Computed c, String ref) {
        return c.view().lines().stream().filter(l -> l.ref().equals(ref)).findFirst().orElseThrow();
    }

    /** The hours per day of a line's cells. */
    private static Map<String, Double> daysOf(MatrixLine l) {
        Map<String, Double> out = new TreeMap<>();
        l.cells().forEach(x -> out.merge(x.day(), x.hours(), Numbers::sum));
        return out;
    }

    /** The hours per day of the step {@code ref}, all its people together. */
    static Map<String, Double> hoursByDay(List<DetailScheduler.Part> parts, String ref) {
        Map<String, Double> out = new TreeMap<>();
        parts.stream().filter(p -> p.ref().equals(ref)).forEach(p -> out.merge(p.date(), p.hours(), Numbers::sum));
        return out;
    }

    /** The labels entered at the onboarding's Setting Phase; none for an anken that was not onboarded. */
    private List<String> labels(String epic) {
        return onboarding.find(epic).map(Onboarding::labels).orElse(List.of());
    }

    /**
     * The plan as it shows, refused while it has input problems; over a budget or over the day's allocation are only warnings
     * (on the screen).
     */
    private Computed pushable(AnkenScope scope, String epic) {
        Computed c = compute(scope, epic);
        if (c.rows().isEmpty()) {
            throw new Invalid("There is nothing to push", "plan");
        }
        if (!c.view().problems().isEmpty()) {
            throw new Invalid(c.view().problems().getFirst(), "plan");
        }
        return c;
    }

    /**
     * Each step's sub-task under its row's Task, assigned by the step's PIC (Settings › Step weights); the BAL reports the
     * Create step, so the function's BAL is read back from Jira. Each gets {@code labels} and the dates the plan shows, none
     * when it has none yet.
     */
    private static List<NewTicket> newTickets(Computed c, List<String> labels) {
        List<NewTicket> out = new ArrayList<>();
        for (EstimationRow r : c.rows()) {
            String parentKey = Objects.requireNonNull(r.taskKey());
            for (PlanRows.RowStep st : c.stepsOf().get(r.id())) {
                if (st.removed()) {
                    continue;
                }
                MatrixLine shown = line(c, st.ref());
                String start = shown.start();
                String end = shown.end();
                String assignee = Objects.requireNonNull(PlanRows.assignee(st, r.baId(), r.balId(), c.weights()));
                out.add(new NewTicket(st.ref(), true, parentKey, null, c.names().getOrDefault(st.ref(), r.functionName()), assignee, reporter(st, r),
                        start, end, st.hours(), labels));
            }
        }
        return out;
    }

    /** The BAL reports the Create step, so the function's BAL is read back from Jira; the other steps keep Jira's default. */
    private static @Nullable String reporter(PlanRows.RowStep st, EstimationRow r) {
        return st.kind() == StepKind.CREATE ? r.balId() : null;
    }

    /** Changes one sub-task of the plan: its effort, its dates or whether it is in the plan. */
    public DetailPlanView editStep(AnkenScope scope, String epic, StepIn in) {
        EstimationService.load(context, scope, epic);
        List<EstimationRow> open = estimation.findByEpic(epic).stream().filter(r -> !r.pushed()).toList();
        EstimationRow row = open.stream().filter(r -> r.id() == in.rowId()).findFirst()
                .orElseThrow(() -> new NotFound("Estimation row " + in.rowId() + " is not in the plan", "row_id"));
        StepKind k;
        try {
            k = Text.hasText(in.step()) ? StepKind.of(in.step()) : null;
        } catch (IllegalArgumentException e) {
            throw new Invalid("Unknown step " + in.step(), "step");
        }
        if (row.steps() == null ? k != null : k == null || !StepWeights.stepsOf(row.steps()).contains(k)) {
            throw new Invalid((k == null ? "A ticket" : k.value()) + " is not a step of " + row.functionName(), "step");
        }
        if ((in.start() == null) != (in.end() == null)) {
            throw new Invalid("Set both Plan start and Plan end", in.start() == null ? "start" : "end");
        }
        if (in.start() != null && in.end().compareTo(in.start()) < 0) {
            throw new Invalid("Plan end is before Plan start", "end");
        }
        Double effort = in.effortH();
        if (effort != null && !(effort >= 0)) {
            throw new Invalid("Effort must be 0 hours or more", "effort_h");
        }
        if (effort != null && (k == null || k == StepKind.CREATE)) { // the row's own effort: the other steps follow it
            double h = effort;
            List<EstimationRow> next = open.stream().map(r -> r.id() != row.id() ? r : new EstimationRow(r.id(), epic, r.position(), r.phase(),
                    r.subphase(), r.functionName(), r.steps(), h, r.baId(), r.balId(), r.jmId(), r.taskKey(), null)).toList();
            estimations.save(scope, epic, next);
            effort = null;
        }
        estimation.saveStepEdit(new StepEdit(row.id(), k, effort, in.start(), in.end(), in.removed()));
        return view(scope, epic);
    }

    /** Adds a function or a ticket to the estimation, after the other rows; its phase is its Task's. */
    public DetailPlanView addRow(AnkenScope scope, String epic, NewRowIn in) {
        List<EstimationRow> next = new ArrayList<>(estimation.findByEpic(epic).stream().filter(r -> !r.pushed()).toList());
        next.add(new EstimationRow(0, epic, next.size(), "", "", Objects.requireNonNullElse(in.functionName(), ""), in.steps(), in.effortH(),
                null, null, null, in.taskKey(), null));
        estimations.save(scope, epic, next);
        return view(scope, epic);
    }
}
