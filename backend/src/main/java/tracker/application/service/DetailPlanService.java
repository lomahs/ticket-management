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
import tracker.domain.Allocations;
import tracker.domain.AutoAllocator;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.AutoPicks;
import tracker.domain.Capacity;
import tracker.domain.Dates;
import tracker.domain.DetailScheduler;
import tracker.domain.Effort;
import tracker.domain.DetailScheduler.Chain;
import tracker.domain.DetailScheduler.DayOverrun;
import tracker.domain.DetailScheduler.Overrun;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.MasterSchedule;
import tracker.domain.PlanRows;
import tracker.domain.StepEdit;
import tracker.domain.Onboarding;
import tracker.domain.PhaseGroups;
import tracker.domain.Role;
import tracker.domain.RuleError;
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
    private final Clock clock;

    public DetailPlanService(TrackerContextLoader context, EstimationRepository estimation, MasterScheduleRepository versions,
                             AllocationService allocations, TicketService tickets, OnboardingRepository onboarding,
                             EstimationService estimations, Clock clock) {
        this.context = context;
        this.estimation = estimation;
        this.versions = versions;
        this.allocations = allocations;
        this.tickets = tickets;
        this.onboarding = onboarding;
        this.estimations = estimations;
        this.clock = clock;
    }

    /** @param baId the BA of a function, or the one member of a row without steps */
    public record RowAssign(long rowId, @Nullable String baId, @Nullable String balId, @Nullable String jmId) {
    }

    /** @param order the unpushed rows' ids in their new order */
    public record DraftIn(List<Long> order, List<RowAssign> assigns) {
    }

    /** @param step the step's value ("create", "balr", …); null for a row without steps */
    public record StepPlan(String ref, @Nullable String step, String memberId, double hours, String start, String end) {
    }

    public record RowPlan(long rowId, List<StepPlan> steps) {
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
     * One sub-task of the plan (a step of a function, or a ticket), as the list shows it.
     *
     * @param step    the step's value ("create", "balr", …); null for a ticket
     * @param summary the sub-task's Summary by Settings › Name rules
     * @param baH     hours the row's BA (a ticket's member) carries on it by the step's shares; {@code balH} the BAL's
     * @param start   null while the plan cannot be computed, and for a removed step
     * @param manual  its dates were set by hand
     * @param edited  its effort was set by hand instead of Create × its weight
     * @param assigneeId who the pushed sub-task is assigned to, by the step's PIC; null while the row has nobody
     * @param reporterId the BAL on a Create step, else null (Jira's default)
     */
    public record StepRow(String ref, long rowId, @Nullable String step, String phase, String summary, double effortH, double baH,
                          double balH, @Nullable String start, @Nullable String end, boolean manual, boolean edited, boolean removed,
                          @Nullable String assigneeId, @Nullable String reporterId) {
    }

    /**
     * What a member has on a day.
     *
     * @param key   the Jira key of a sub-task already in Jira, the anken's epic key for work on another anken; null for a
     *              sub-task of this plan
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
     * @param steps       every sub-task of the unpushed rows, removed ones included, in row order
     * @param days        the members with hours in the plan, day by day from its first start to its last end
     * @param stalePhases phases whose BA effort changed since the master schedule was saved (its End is out of date)
     * @param problems    what keeps a plan from being computed; when any, {@code plan} is empty
     * @param allocated   one per entry of {@code overBudget}, in the same order
     * @param byHand      the slots of the unpushed rows picked by hand (not by Auto allocate), for the rows with any
     */
    public record DetailPlanView(List<EstimationRow> rows, List<EstimationService.TaskOption> tasks, List<RowPlan> plan, List<StepRow> steps,
                                 List<Overrun> overBudget, List<OverrunAllocation> allocated, List<DayOverrun> overDay,
                                 List<PhaseCheck> checks, List<DayLoad> days, List<String> stalePhases, List<String> problems,
                                 List<HandPicks> byHand) {
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
                    Map<String, String> names, List<Placed> placed, StepWeights weights, DetailPlanView view) {
    }

    public DetailPlanView view(AnkenScope scope, String epic) {
        return compute(scope, epic).view();
    }

    /** Saves the order of the unpushed rows and their people, picked by hand; returns the plan that follows. */
    public DetailPlanView saveDraft(AnkenScope scope, String epic, DraftIn draft) {
        return saveDraft(scope, epic, draft, true);
    }

    /**
     * @param byHand the people come from the PIM: a slot that changes is no longer Auto allocate's pick; else from Auto
     *               allocate, which remembers who it put in each slot it filled
     */
    private DetailPlanView saveDraft(AnkenScope scope, String epic, DraftIn draft, boolean byHand) {
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
        return view(scope, epic);
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
     * JMs from the phase's team in the master schedule. People already picked stay; the draft is saved.
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
        return saveDraft(scope, epic, new DraftIn(open.stream().map(EstimationRow::id).toList(), assigns), false);
    }

    private static @Nullable String blank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    Computed compute(AnkenScope scope, String epic) {
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
        List<String> problems = problems(ctx, epic, schedule, rows, stepsOf, w, tasks);
        List<Placed> placed = List.of();
        List<Overrun> over = List.of();
        List<OverrunAllocation> allocated = List.of();
        List<DayOverrun> overDay = List.of();
        Map<String, Map<String, Double>> ledger = Map.of();
        List<DetailScheduler.Part> parts = List.of();
        Allocations a = allocations.counted(scope).allocations();
        double jmHoursPerDay = ctx.settings.workingHours.hoursPerDay();
        if (problems.isEmpty() && !rows.isEmpty()) {
            Map<String, PhaseRow> phases = phases(schedule);
            List<Chain> chains = new ArrayList<>();
            for (EstimationRow r : rows) {
                Chain c = PlanRows.chain(r.phase(), stepsOf.get(r.id()), r.baId(), r.balId(), r.jmId(), w, phases.get(r.phase()).start());
                if (c != null) {
                    chains.add(c);
                }
            }
            try {
                DetailScheduler.Result res = DetailScheduler.schedule(epic, chains, loads(ctx, epic, Set.of()), a, ctx.cal, jmHoursPerDay,
                        w.daySplit());
                placed = res.placed();
                overDay = res.overDay();
                ledger = res.ledger();
                parts = res.parts();
                over = DetailScheduler.overBudget(placed, givenBefore(ctx, epic, Set.of()), phases, epic, a, ctx.cal);
                allocated = over.stream().map(o -> allocated(ctx, epic, a, o.memberId(), o.phase(), phases.get(o.phase()).start())).toList();
            } catch (RuleError e) {
                problems = List.of(e.getMessage());
            }
        }
        Map<String, List<Placed>> byRef = new LinkedHashMap<>();
        placed.forEach(p -> byRef.computeIfAbsent(p.ref(), k -> new ArrayList<>()).add(p));
        Map<String, String> names = summaries(ctx, epic, onboarding.find(epic).map(Onboarding::ankenPrefix).orElse(null), rows, stepsOf);
        List<RowPlan> plan = new ArrayList<>();
        List<StepRow> stepRows = new ArrayList<>();
        for (EstimationRow r : rows) {
            List<StepPlan> steps = new ArrayList<>();
            for (PlanRows.RowStep st : stepsOf.get(r.id())) {
                List<Placed> ps = byRef.getOrDefault(st.ref(), List.of());
                String start = ps.stream().map(Placed::start).min(String::compareTo).orElse(null);
                String end = ps.stream().map(Placed::end).max(String::compareTo).orElse(null);
                if (!ps.isEmpty()) {
                    steps.add(new StepPlan(st.ref(), st.kind() == null ? null : st.kind().value(), ps.getFirst().memberId(), st.hours(),
                            Objects.requireNonNull(start), Objects.requireNonNull(end)));
                }
                double[] h = st.kind() != null && !w.shareOf(st.kind()).counted() ? new double[]{0, 0} : PlanRows.split(st, w);
                stepRows.add(new StepRow(st.ref(), r.id(), st.kind() == null ? null : st.kind().value(), r.phase(),
                        names.getOrDefault(st.ref(), r.functionName()), st.hours(), h[0], h[1], start, end, st.pinned(),
                        st.edit() != null && st.edit().effortH() != null, st.removed(), PlanRows.assignee(st, r.baId(), r.balId(), w),
                        reporter(st, r)));
            }
            if (!steps.isEmpty()) {
                plan.add(new RowPlan(r.id(), steps));
            }
        }
        List<PhaseCheck> checks = schedule == null ? List.of() : checks(schedule, placed);
        List<DayLoad> days = days(ctx, epic, a, ledger, placed, parts, names);
        List<String> stale = new ArrayList<>();
        if (schedule != null) {
            Map<String, MasterSchedule.PhaseWork> work = MasterSchedule.work(all, edits, w, jmHoursPerDay);
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
        DetailPlanView view = new DetailPlanView(all, tasks, plan, stepRows, over, allocated, overDay, checks, days, stale, problems, byHand);
        return new Computed(ctx, schedule, rows, stepsOf, names, placed, w, view);
    }

    /**
     * The members of the anken with hours in the plan's window, each day from its first start to its last end, with what makes
     * up their hours: this plan's sub-tasks, the anken's open sub-tasks in Jira, and their open sub-tasks on the other tracked
     * anken (by anken).
     */
    private List<DayLoad> days(TrackerContext ctx, String epic, Allocations a, Map<String, Map<String, Double>> ledger, List<Placed> placed,
                               List<DetailScheduler.Part> parts, Map<String, String> names) {
        if (placed.isEmpty()) {
            return List.of();
        }
        String from = placed.stream().map(Placed::start).min(String::compareTo).orElseThrow();
        String to = placed.stream().map(Placed::end).max(String::compareTo).orElseThrow();
        List<String> window = Dates.daysBetween(from, to);
        Capacity.Plan jira = loadPlan(ctx, epic, Set.of());
        Map<String, Map<String, List<DayItem>>> mine = new HashMap<>();
        for (DetailScheduler.Part p : parts) {
            mine.computeIfAbsent(p.memberId(), k -> new HashMap<>()).computeIfAbsent(p.date(), k -> new ArrayList<>())
                    .add(new DayItem(null, names.getOrDefault(p.ref(), p.ref()), p.hours(), false));
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
                Capacity.DayPlan dp = jira.day(m, d);
                if (dp != null) {
                    dp.byKey.forEach((key, h) -> items.add(new DayItem(key, ctx.issue(key).map(Issue::summary).orElse(key), h, false)));
                }
                Map<String, Double> byEpic = new TreeMap<>();
                Capacity.DayPlan op = other.day(m, d);
                if (op != null) {
                    op.byKey.forEach((key, h) -> byEpic.merge(epicOf.get(key), h, Double::sum));
                }
                byEpic.forEach((e, h) -> items.add(new DayItem(e, all.anken(e).map(Anken::title).orElse(e), h, true)));
                String off = ctx.cal.off(d, m);
                out.add(new DayLoad(m, d, day.getOrDefault(d, 0.0), Objects.requireNonNullElse(a.hoursOn(m, epic, d), 0.0),
                        Text.hasText(off) ? off : null, byEpic.values().stream().mapToDouble(Double::doubleValue).sum(), items));
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
                            i.estimateH() * sh.fraction(), Double::sum);
                }
            }
        }
        return out;
    }

    static List<PhaseCheck> checks(ScheduleVersion schedule, List<Placed> placed) {
        List<PhaseCheck> out = new ArrayList<>();
        for (PhaseRow p : schedule.phases()) {
            String end = placed.stream().filter(x -> x.phase().equals(p.phase())).map(Placed::end).max(String::compareTo).orElse(null);
            out.add(new PhaseCheck(p.phase(), p.end(), end, end == null ? 0 : Dates.dayDiff(p.end(), end)));
        }
        return out;
    }

    /**
     * The review of the push: every sub-task, with the request that creates it.
     *
     * @param allowOverDay the PIM confirmed the days planned above a member's allocation (a warning, not a block)
     */
    public BatchPreview pushPreview(AnkenScope scope, String epic, boolean allowOverDay) {
        Computed c = pushable(scope, epic, allowOverDay);
        return tickets.previewBatch(scope, epic, List.of(), newTickets(c, labels(epic)));
    }

    /**
     * Creates the sub-tasks of the unpushed rows under their Tasks; a row is marked pushed once all its sub-tasks exist, also
     * when Jira refuses a request half-way.
     */
    public BatchResult push(Viewer actor, AnkenScope scope, String epic, boolean allowOverDay) {
        Computed c = pushable(scope, epic, allowOverDay);
        List<NewTicket> creates = newTickets(c, labels(epic));
        return tickets.applyBatch(actor, scope, epic, List.of(), creates, created -> {
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

    /** The labels entered at the onboarding's Setting Phase; none for an anken that was not onboarded. */
    private List<String> labels(String epic) {
        return onboarding.find(epic).map(Onboarding::labels).orElse(List.of());
    }

    /** The computed plan, refused when it cannot be pushed as it is; days over the allocation only once confirmed. */
    private Computed pushable(AnkenScope scope, String epic, boolean allowOverDay) {
        Computed c = compute(scope, epic);
        DetailPlanView v = c.view();
        if (c.rows().isEmpty()) {
            throw new Invalid("There is nothing to push", "plan");
        }
        if (!v.problems().isEmpty()) {
            throw new Invalid(v.problems().getFirst(), "plan");
        }
        if (!v.overBudget().isEmpty()) {
            Overrun o = v.overBudget().getFirst();
            throw new Invalid(c.ctx().members.get(o.memberId()).name() + " is over budget in " + o.phase() + ": " + o.plannedH() + "h of "
                    + o.budgetH() + "h", "plan");
        }
        if (!v.overDay().isEmpty() && !allowOverDay) {
            DayOverrun o = v.overDay().getFirst();
            throw new Invalid(c.ctx().members.get(o.memberId()).name() + " has " + o.plannedH() + "h planned on " + o.date() + ", more than the "
                    + o.allocationH() + "h allocation: confirm to push anyway", "allow_over_day");
        }
        return c;
    }

    /**
     * Each step's sub-task under its row's Task, assigned by the step's PIC (Settings › Step weights); the BAL reports the
     * Create step, so the function's BAL is read back from Jira. Each gets {@code labels}.
     */
    private static List<NewTicket> newTickets(Computed c, List<String> labels) {
        List<NewTicket> out = new ArrayList<>();
        for (EstimationRow r : c.rows()) {
            String parentKey = Objects.requireNonNull(r.taskKey());
            for (PlanRows.RowStep st : c.stepsOf().get(r.id())) {
                List<Placed> ps = c.placed().stream().filter(p -> p.ref().equals(st.ref())).toList();
                if (st.removed() || ps.isEmpty()) {
                    continue;
                }
                String start = ps.stream().map(Placed::start).min(String::compareTo).orElseThrow();
                String end = ps.stream().map(Placed::end).max(String::compareTo).orElseThrow();
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
