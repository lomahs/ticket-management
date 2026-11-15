package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.TrackerContext;
import tracker.application.JiraValues;
import tracker.application.TrackerContextLoader;
import tracker.application.dto.IssueSnapshot;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.JiraReader;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.ports.OnboardingRepository;
import tracker.application.ports.PlanCellRepository;
import tracker.application.ports.PlanCellRepository.Cell;
import tracker.application.ports.PlanCellRepository.JiraEdit;
import tracker.application.ports.PlanDayRepository;
import tracker.application.ports.PlanVersionRepository;
import tracker.application.settings.TrackerSettings.FieldMapping;
import tracker.domain.Allocations;
import tracker.domain.AutoAllocator;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.Baseline;
import tracker.domain.AutoPicks;
import tracker.domain.Capacity;
import tracker.domain.Dates;
import tracker.domain.Scheduling;
import tracker.domain.DetailMatrix;
import tracker.domain.DetailScheduler;
import tracker.domain.DraftAnken;
import tracker.domain.Effort;
import tracker.domain.DetailScheduler.DayOverrun;
import tracker.domain.DetailScheduler.Overrun;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.MasterSchedule;
import tracker.domain.Numbers;
import tracker.domain.PlanRows;
import tracker.domain.RuleError;
import tracker.domain.StepEdit;
import tracker.domain.Onboarding;
import tracker.domain.PhaseGroups;
import tracker.domain.Role;
import tracker.domain.ScheduleVersion;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Steps;
import tracker.domain.StepWeights;
import tracker.domain.Text;
import tracker.domain.Viewer;
import tracker.application.service.AnkenService.ParentOption;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.TicketService.ExpectedChange;
import tracker.application.service.TicketService.ExpectedUpdate;
import tracker.application.service.TicketService.NewTicket;
import tracker.application.service.TicketService.TicketChanges;
import tracker.application.service.TicketService.TicketUpdate;

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
    private final JiraReader reader;
    private final Clock clock;
    private final TransactionTemplate tx;

    public DetailPlanService(TrackerContextLoader context, EstimationRepository estimation, MasterScheduleRepository versions,
                             AllocationService allocations, TicketService tickets, OnboardingRepository onboarding,
                             EstimationService estimations, PlanDayRepository planDays, PlanCellRepository cells,
                             PlanVersionRepository planVersions, JiraReader reader, Clock clock, TransactionTemplate tx) {
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
        this.reader = reader;
        this.clock = clock;
        this.tx = tx;
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
     * @param planEstimateH Plan Estimation: a row's step effort; a sub-task's Original Estimate (planning only, no actuals)
     * @param planH         Planned effort: the hours of every day in {@code cells}
     * @param planNotYetH   Not yet plan effort: Plan Estimation − Planned effort
     * @param subphase      the Task the line's function goes under, by its name (a row's subphase; a sub-task's parent); empty if none
     * @param picSlot       a row's step only: whose person the step's PIC cell picks — "jm" on JM Review (the JM carries it in
     *                      the plan), else the step's PIC by Settings › Step weights ("ba" or "bal"); "ba" on a ticket
     * @param linkedRowId   a sub-task in Jira only: the unpushed row it is a step of (same Task and function), which shows its
     *                      other steps; null when none
     */
    // issue_key named outright: the OpenAPI resolver leaves "issueKey" (a name starting "is") unconverted
    public record MatrixLine(String ref, @Nullable Long rowId, @JsonProperty("issue_key") @Nullable String issueKey, String phase, String function,
                             @Nullable String step, String summary, @Nullable String picId, @Nullable String reporterId,
                             double totalH, double spentH, double plannedH, double notYetH, @Nullable String start, @Nullable String end,
                             List<DayHours> cells, List<DayHours> logged, boolean closed, boolean pending, boolean outOfOrder,
                             boolean removed, boolean edited, double planEstimateH, double planH, double planNotYetH,
                             String subphase, @Nullable String picSlot, @Nullable Long linkedRowId) {
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
     */
    public record StepIn(long rowId, @Nullable String step, @Nullable Double effortH, boolean removed) {
    }

    /** Hours typed in one cell: {@code ref} is a step's ref of an unpushed row or the key of a sub-task in Jira; 0 clears it. */
    public record CellIn(String ref, String day, double hours) {
    }

    /** The lines (refs) of the functions to shift, and by how many working days: negative is earlier. */
    public record ShiftIn(List<String> refs, int days) {
    }

    /**
     * A change to a sub-task in Jira, kept until the push.
     *
     * @param totalH Spent + Remaining wanted; null keeps it
     * @param picId  null keeps it
     */
    public record JiraLineIn(@Nullable Double totalH, @Nullable String picId) {
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

    /**
     * Saves the plan as it shows (rows, and per line its PIC, Total and cells) as a version; returns the plan that follows.
     *
     * @param baseline from Planning → Detail plan: refused until every line is in Jira, nothing waits to be pushed and Jira
     *                 still has what the app has; the version then keeps each sub-task's Original Estimate, Task, plan owner
     *                 and hours per day, and the first such version is the anken's Baseline plan. Onboarding saves without.
     */
    public DetailPlanView saveVersion(Viewer actor, AnkenScope scope, String epic, boolean baseline) {
        DetailPlanView v = view(scope, epic);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EstimationRow r : v.rows()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", r.id());
            o.put("ba_id", r.baId());
            o.put("bal_id", r.balId());
            o.put("jm_id", r.jmId());
            rows.add(o);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (MatrixLine l : v.lines()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("ref", l.ref());
            o.put("summary", l.summary());
            o.put("pic_id", l.picId());
            o.put("total_h", l.totalH());
            Map<String, Double> cellMap = new LinkedHashMap<>();
            l.cells().forEach(c -> cellMap.put(c.day(), c.hours()));
            o.put("cells", cellMap);
            lines.add(o);
        }
        Map<String, Object> plan = Map.of("rows", rows, "lines", lines);
        String body = baseline ? Baselines.body(plan, baselineItems(context.load(scope), epic, v)) : Baselines.write(plan);
        planVersions.add(epic, Instant.now(clock).toString(), actor.name(), body, baseline);
        return view(scope, epic);
    }

    /** What a version keeps for the Baseline plan, once the plan is in Jira and up to date with it. */
    private List<Baseline.Item> baselineItems(TrackerContext ctx, String epic, DetailPlanView v) {
        List<String> refused = new ArrayList<>();
        if (DraftAnken.isKey(epic)) {
            refused.add("Link the anken's epic in Jira first");
        }
        long open = v.rows().stream().filter(r -> !r.pushed()).count();
        if (open > 0) {
            refused.add(open + (open == 1 ? " row is" : " rows are") + " not in Jira yet: push first");
        }
        long pending = v.lines().stream().filter(l -> l.issueKey() != null && l.pending()).count();
        if (pending > 0) {
            refused.add(pending + (pending == 1 ? " sub-task has" : " sub-tasks have") + " changes not pushed: push or clear them first");
        }
        if (!refused.isEmpty()) {
            throw new Invalid("A version is saved once the plan is in Jira. " + String.join("; ", refused), "plan");
        }
        List<Issue> subs = ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic)).toList();
        List<String> differ = differFromJira(ctx, epic, subs);
        if (!differ.isEmpty()) {
            throw new Invalid("Jira has changed since the last sync: " + String.join("; ", differ) + ". Sync, then save the version again", "plan");
        }
        Map<String, MatrixLine> byKey = new HashMap<>();
        v.lines().stream().filter(l -> l.issueKey() != null).forEach(l -> byKey.put(l.issueKey(), l));
        List<Baseline.Item> out = new ArrayList<>();
        for (Issue i : subs) {
            // the sub-tasks the burndown draws: dated, with an estimate, JM Review aside
            if (!Text.hasText(i.plannedStart()) || !Text.hasText(i.plannedEnd()) || i.estimateH() <= 0 || Steps.isJmReview(i)) {
                continue;
            }
            MatrixLine l = byKey.get(i.key());
            Map<String, Double> days = new TreeMap<>();
            if (l != null) {
                l.cells().forEach(c -> days.merge(c.day(), c.hours(), Numbers::sum));
            } else {
                days.putAll(ctx.planDays(i));
            }
            out.add(new Baseline.Item(i.key(), i.estimateH(), i.parentKey(), Objects.requireNonNullElse(ctx.planOwner(i), ""), days));
        }
        return out;
    }

    /**
     * The sub-tasks whose Original Estimate or planned dates in Jira differ from the app's, and the tickets in Jira the app has
     * not synced yet: "KEY: what differs".
     */
    private List<String> differFromJira(TrackerContext ctx, String epic, List<Issue> subs) {
        FieldMapping fm = ctx.settings.fieldMapping;
        Map<String, IssueSnapshot> live = new HashMap<>();
        reader.search(List.of(epic), null).forEach(s -> live.put(s.key(), s));
        List<String> out = new ArrayList<>();
        Set<String> known = new HashSet<>();
        ctx.parents().forEach(i -> known.add(i.key()));
        ctx.subtasks().forEach(i -> known.add(i.key()));
        live.keySet().stream().filter(k -> !known.contains(k)).sorted().forEach(k -> out.add(k + ": not synced yet"));
        for (Issue i : subs) {
            IssueSnapshot s = live.get(i.key());
            if (s == null) {
                out.add(i.key() + ": not found in Jira");
                continue;
            }
            double est = Objects.requireNonNullElse(JiraValues.hours(s.fields().get(fm.effort), fm.effort), 0.0);
            if (Math.abs(est - i.estimateH()) > 0.01) {
                out.add(i.key() + ": Original Estimate " + Numbers.format(est) + "h in Jira, " + Numbers.format(i.estimateH()) + "h here");
            }
            String start = JiraValues.date(s.fields().get(fm.plannedStart));
            if (!Objects.equals(Text.orElse(start, ""), Text.orElse(i.plannedStart(), ""))) {
                out.add(i.key() + ": Planned Start " + Text.orElse(start, "none") + " in Jira, " + Text.orElse(i.plannedStart(), "none") + " here");
            }
            String end = JiraValues.date(s.fields().get(fm.plannedEnd));
            if (!Objects.equals(Text.orElse(end, ""), Text.orElse(i.plannedEnd(), ""))) {
                out.add(i.key() + ": Planned End " + Text.orElse(end, "none") + " in Jira, " + Text.orElse(i.plannedEnd(), "none") + " here");
            }
        }
        return out;
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
     * Empties the slots of the unpushed rows and the cells. {@code keepInput}: only the slots still holding Auto allocate's pick
     * and the cells it filled (of the unpushed rows and the sub-tasks in Jira); the people picked by hand, the cells typed, the
     * efforts set by hand, the removed steps and the pending Jira changes stay. Else every slot, every cell of the unpushed rows
     * and every step edit goes, and every pending Jira change is dropped: the plan is the estimation's and Jira's again.
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
        cells.deleteRowCells(epic, keepInput);
        cells.deleteJiraAll(epic, keepInput);
        return view(scope, epic);
    }

    private static @Nullable String keep(@Nullable String picked, @Nullable String auto, boolean keepInput) {
        return keepInput && AutoPicks.byHand(picked, auto) ? picked : null;
    }

    /**
     * Fills the empty BA / BAL / JM slots of the unpushed rows ({@link AutoAllocator}, docs/auto-allocation.md): BAs, BALs and
     * JMs from the phase's team in the master schedule. People already picked stay; the draft is saved. Refused with the first
     * input problem left, the people staying. Then fills what each line has not planned yet ({@link #fill}): the cells already
     * there stay.
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
        RowLinks links = link(ctx, epic, edits, w);
        // the steps already in Jira keep Jira's plan; a row with all of them there is pushed
        open = open.stream().filter(r -> !links.whole().contains(r.id())).toList();
        AutoAllocator.Input in = new AutoAllocator.Input(epic,
                open.stream().map(r -> new AutoAllocator.Row(r.id(), r.phase(), r.steps(), r.effortH(), r.baId(), r.balId(), r.jmId(),
                        links.open(PlanRows.steps(r.id(), r.steps(), r.effortH(), edits, w)))).toList(),
                phases, roles, loads(ctx, epic, Set.of()), givenBefore(ctx, epic, Set.of()),
                AutoAllocator.cached(DetailScheduler.hours(epic, a, ctx.cal)), ctx.cal, w, ctx.settings.workingHours.hoursPerDay());
        List<RowAssign> assigns;
        try {
            assigns = AutoAllocator.allocate(in).stream().map(p -> new RowAssign(p.rowId(), p.baId(), p.balId(), p.jmId())).toList();
        } catch (RuleError e) { // the people of a row cannot take it
            throw new Invalid(e.getMessage(), "plan");
        }
        saveDraft(scope, epic, new DraftIn(open.stream().map(EstimationRow::id).toList(), assigns), false);
        List<String> problems = basis(scope, epic).problems();
        if (!problems.isEmpty()) {
            throw new Invalid(problems.getFirst(), "plan");
        }
        fill(scope, epic, a);
        return view(scope, epic);
    }

    /**
     * Trims the sub-tasks in Jira planned over their total ({@link MatrixFill#trims}), then fills the Not yet of every line
     * ({@link MatrixFill}) as Auto allocate's cells, all in one transaction. Hours added on a day that already has a cell join it,
     * which keeps whether it was typed; a sub-task in Jira without pending cells first keeps Jira's plan from today on. Refused
     * (nothing written) when the people of an unpushed row cannot take it; a sub-task in Jira they cannot take keeps its Not yet.
     */
    private void fill(AnkenScope scope, String epic, Allocations a) {
        tx.executeWithoutResult(status -> {
            Computed c = compute(scope, epic);
            fill(epic, trim(epic, c) ? compute(scope, epic) : c, a); // what was trimmed is free again
        });
    }

    /** Writes Auto allocate's trims of the sub-tasks in Jira planned over their total; whether there were any. */
    private boolean trim(String epic, Computed c) {
        Map<String, List<Cell>> pending = cells.jiraCells(epic);
        Map<String, Map<String, Double>> cut = MatrixFill.trims(c.view().lines(), pending.keySet(), c.ctx().today);
        cut.forEach((key, days) -> {
            MatrixLine l = line(c, key);
            Map<String, Boolean> auto = new HashMap<>();
            l.cells().forEach(d -> auto.put(d.day(), d.auto()));
            seed(epic, l, pending, c.ctx().today);
            cells.saveJiraCells(epic, days.entrySet().stream().map(e -> new Cell(key, e.getKey(), e.getValue(), auto.getOrDefault(e.getKey(), false)))
                    .toList());
        });
        return !cut.isEmpty();
    }

    private void fill(String epic, Computed c, Allocations a) {
        TrackerContext ctx = c.ctx();
        List<MatrixLine> lines = c.view().lines();
        Map<String, MatrixLine> lineOf = new HashMap<>();
        lines.forEach(l -> lineOf.put(l.ref(), l));
        Map<String, List<Effort.PlanShare>> jiraShares = new HashMap<>();
        Map<String, String> jiraChains = new HashMap<>();
        for (Issue i : openSubtasks(ctx, epic, Set.of())) {
            MatrixLine l = lineOf.get(i.key());
            if (l != null) {
                jiraShares.put(i.key(), PlanLines.shares(ctx.planShares(i), i.assigneeId(), l.picId()));
                jiraChains.put(i.key(), PlanLines.chain(i));
            }
        }
        Map<String, Map<String, Double>> added;
        try {
            added = MatrixFill.fill(epic, lines, c.rows(), c.stepsOf(), phases(c.schedule()), jiraShares, jiraChains, c.parts(), a, ctx.cal,
                    ctx.today, ctx.settings.workingHours.hoursPerDay(), c.weights());
        } catch (RuleError e) {
            throw new Invalid(e.getMessage(), "plan");
        }
        save(epic, ctx.today, lineOf, added);
    }

    /** Writes what Auto allocate adds: on a day with a cell, the sum, which keeps whether it was typed; else a filled cell. */
    private void save(String epic, String today, Map<String, MatrixLine> lineOf, Map<String, Map<String, Double>> added) {
        List<Cell> rowCells = new ArrayList<>();
        Map<String, List<Cell>> pending = cells.jiraCells(epic);
        added.forEach((ref, days) -> {
            MatrixLine l = lineOf.get(ref);
            Map<String, DayHours> had = new HashMap<>();
            l.cells().forEach(d -> had.put(d.day(), d));
            List<Cell> out = new ArrayList<>();
            days.forEach((day, h) -> {
                DayHours was = had.get(day);
                out.add(was == null || was.hours() <= 0 ? new Cell(ref, day, h, true) : new Cell(ref, day, Numbers.sum(was.hours(), h), was.auto()));
            });
            if (l.issueKey() == null) {
                rowCells.addAll(out);
            } else {
                seed(epic, l, pending, today);
                cells.saveJiraCells(epic, out);
            }
        });
        cells.saveRowCells(rowCells);
    }

    private static @Nullable String blank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** What the plan of an anken's unpushed rows is made from: its rows, their steps, the master schedule and what is missing. */
    /** @param links the rows' steps already in Jira: {@code stepsOf} leaves them out */
    private record Basis(TrackerContext ctx, List<EstimationRow> all, List<EstimationRow> rows, List<StepEdit> edits, StepWeights w,
                         Map<Long, List<PlanRows.RowStep>> stepsOf, @Nullable ScheduleVersion schedule,
                         List<EstimationService.TaskOption> tasks, List<String> problems, RowLinks links) {
    }

    private Basis basis(AnkenScope scope, String epic) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        List<StepEdit> edits = estimation.stepEdits(epic);
        StepWeights w = ctx.settings.stepWeightsOf(epic);
        RowLinks links = link(ctx, epic, edits, w);
        List<EstimationRow> all = estimation.findByEpic(epic);
        List<EstimationRow> rows = all.stream().filter(r -> !r.pushed()).toList();
        Map<Long, List<PlanRows.RowStep>> stepsOf = new LinkedHashMap<>();
        rows.forEach(r -> stepsOf.put(r.id(), links.open(PlanRows.steps(r.id(), r.steps(), r.effortH(), edits, w))));
        List<ScheduleVersion> saved = versions.findByEpic(epic);
        ScheduleVersion schedule = saved.isEmpty() ? null : saved.getLast();
        List<EstimationService.TaskOption> tasks = EstimationService.tasks(ctx, epic);
        return new Basis(ctx, all, rows, edits, w, stepsOf, schedule, tasks, problems(ctx, epic, schedule, rows, stepsOf, w, tasks), links);
    }

    /**
     * Links the unpushed rows to their function in Jira ({@link RowLinks}): a row whose steps are all there is marked pushed, as
     * a push would have (Jira's plan stands), whoever made the sub-tasks (a push cut short, the CSV export, the client).
     */
    private RowLinks link(TrackerContext ctx, String epic, List<StepEdit> edits, StepWeights w) {
        List<EstimationRow> open = estimation.findByEpic(epic).stream().filter(r -> !r.pushed()).toList();
        Map<Long, List<PlanRows.RowStep>> stepsOf = new HashMap<>();
        open.forEach(r -> stepsOf.put(r.id(), PlanRows.steps(r.id(), r.steps(), r.effortH(), edits, w)));
        RowLinks links = RowLinks.of(ctx, epic, open, stepsOf);
        if (!links.whole().isEmpty()) {
            estimation.markPushed(List.copyOf(links.whole()), Instant.now(clock).toString());
        }
        return links;
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
                cells.jiraEdits(epic), b.links());
        List<MatrixLine> lines = built.lines();
        List<Placed> placed = built.placed();
        List<DetailScheduler.Part> parts = built.parts();
        Map<String, Map<String, Double>> ledger = ledger(parts);
        List<DayOverrun> overDay = overDay(ledger, parts, hours, ctx.today);
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
                b.problems(), pushBlockers(epic, lines), byHand, planVersions.lastSavedAt(epic));
        return new Computed(ctx, schedule, rows, stepsOf, names, placed, parts, w, view);
    }

    /** Working days shown ahead of today, whatever the plan holds: a late anken still has days to plan on. */
    static final int DAYS_AHEAD = 20;

    /**
     * Working days from the earliest of today, the master schedule's first Start and the first cell of a line to the latest of
     * the {@value #DAYS_AHEAD}th working day after today, its last End and the last cell (removed and Closed lines aside).
     */
    private static List<String> window(TrackerContext ctx, @Nullable ScheduleVersion schedule, List<MatrixLine> lines) {
        String from = ctx.today;
        String to = ctx.today;
        java.time.LocalDate d = java.time.LocalDate.parse(ctx.today);
        for (int ahead = 0; ahead < DAYS_AHEAD; ) {
            d = d.plusDays(1);
            if (ctx.cal.visible(d.toString())) {
                ahead++;
                to = d.toString();
            }
        }
        for (PhaseRow p : schedule == null ? List.<PhaseRow>of() : schedule.phases()) {
            from = min(from, p.start());
            to = max(to, p.end());
        }
        for (MatrixLine l : lines.stream().filter(l -> !l.removed() && !l.closed()).toList()) {
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

    /**
     * Why Push is refused: a draft anken has no epic to push under; then line by line (removed and Closed lines aside), hours not
     * planned yet, planned over the total, step order.
     */
    private static List<String> pushBlockers(String epic, List<MatrixLine> lines) {
        List<String> out = new ArrayList<>();
        if (DraftAnken.isKey(epic)) {
            out.add("Link the anken's epic in Jira first: its Tasks and sub-tasks are created under it");
        }
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

    /**
     * The days from {@code today} on that the plan gives a member hours on where the ledger has more than the member's hours on
     * the anken (the days before are locked).
     */
    private static List<DayOverrun> overDay(Map<String, Map<String, Double>> ledger, List<DetailScheduler.Part> parts, DetailScheduler.Hours hours,
                                            String today) {
        Map<String, Set<String>> touched = new TreeMap<>();
        parts.stream().filter(p -> p.date().compareTo(today) >= 0)
                .forEach(p -> touched.computeIfAbsent(p.memberId(), k -> new TreeSet<>()).add(p.date()));
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

    /** What a member is allocated to on {@code date}: this anken, then the other anken in the viewer's scope with hours. */
    private static OverrunAllocation allocated(TrackerContext ctx, String epic, Allocations a, String memberId, String phase, String date) {
        List<AnkenAllocation> out = new ArrayList<>();
        out.add(new AnkenAllocation(epic, title(ctx, epic), Objects.requireNonNullElse(a.hoursOn(memberId, epic, date), 0.0)));
        for (String e : ctx.settings.jira.epics) {
            Double h = e.equals(epic) || ctx.anken(e).isEmpty() ? null : a.hoursOn(memberId, e, date);
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

    /** The review of the push: every change to a sub-task in Jira, and every sub-task to create with the request that creates it. */
    public BatchPreview pushPreview(AnkenScope scope, String epic) {
        Computed c = pushable(scope, epic);
        return tickets.previewBatch(scope, epic, jiraUpdates(c, epic), newTickets(c, labels(epic)));
    }

    /**
     * Sends what the plan holds: the changes of the sub-tasks in Jira, then the sub-tasks of the unpushed rows under their Tasks.
     * A row is marked pushed once all its sub-tasks exist, also when Jira refuses a request half-way. A sub-task in Jira whose
     * changes were all sent keeps the plan's hours per day (Jira's past days and the line's cells from today on) and loses its
     * pending changes; the others keep them. A pending line that differs from Jira only in its hours per day sends nothing, and
     * is kept the same way.
     */
    public BatchResult push(Viewer actor, AnkenScope scope, String epic) {
        Computed c = pushable(scope, epic);
        List<NewTicket> creates = newTickets(c, labels(epic));
        List<TicketUpdate> wanted = jiraUpdates(c, epic);
        BatchPreview review = tickets.previewBatch(scope, epic, wanted, List.of());
        List<ExpectedUpdate> updates = review.updates().stream().map(p -> new ExpectedUpdate(p.key(),
                p.changes().stream().map(f -> new ExpectedChange(f.field(), f.before(), f.after())).toList())).toList();
        Set<String> sendsNothing = new HashSet<>(wanted.stream().map(TicketUpdate::key).toList());
        updates.forEach(u -> sendsNothing.remove(u.key()));
        Consumer<List<String>> kept = keys -> keys.forEach(key -> keepPlan(c, key));
        if (updates.isEmpty() && creates.isEmpty()) {
            kept.accept(List.copyOf(sendsNothing));
            return new BatchResult(List.of(), Map.of());
        }
        BatchResult r = tickets.applyBatch(actor, scope, epic, updates, creates, created -> {
            List<Long> done = new ArrayList<>();
            for (EstimationRow row : c.rows()) { // a row whose steps were all removed has nothing to create and is done too
                List<String> refs = creates.stream().map(NewTicket::ref).filter(ref -> rowOf(ref) == row.id()).toList();
                if (created.keySet().containsAll(refs)) {
                    done.add(row.id());
                }
            }
            // marked first: a row left unmarked would create its sub-tasks again on the next push
            if (!done.isEmpty()) {
                estimation.markPushed(done, Instant.now(clock).toString());
            }
            created.forEach((ref, key) -> planDays.save(key, daysOf(line(c, ref))));
        }, kept);
        kept.accept(List.copyOf(sendsNothing));
        return r;
    }

    /** The plan's days of a sub-task in Jira (its past days and the line's cells from today) become its plan; its pending changes go. */
    private void keepPlan(Computed c, String key) {
        tx.executeWithoutResult(status -> {
            planDays.save(key, daysOf(line(c, key)));
            cells.deleteJira(key);
        });
    }

    /**
     * One update per pending sub-task in Jira that is not Closed: its PIC if it differs, its edited Remaining, and Planned Start /
     * End only when its hours per day changed (a PIC or Total edit alone keeps Jira's dates).
     */
    private List<TicketUpdate> jiraUpdates(Computed c, String epic) {
        Map<String, JiraEdit> edits = new HashMap<>();
        cells.jiraEdits(epic).forEach(e -> edits.put(e.issueKey(), e));
        Set<String> withCells = cells.jiraCells(epic).keySet();
        List<TicketUpdate> out = new ArrayList<>();
        for (MatrixLine l : c.view().lines()) {
            if (l.issueKey() == null || !l.pending() || l.closed()) {
                continue;
            }
            JiraEdit e = edits.get(l.issueKey());
            boolean days = withCells.contains(l.issueKey());
            out.add(new TicketUpdate(l.issueKey(), new TicketChanges(null, e == null ? null : e.assigneeId(), null, days ? l.start() : null,
                    days ? l.end() : null, null, null, e == null ? null : e.remainingH())));
        }
        return out;
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
        if (c.rows().isEmpty() && c.view().lines().stream().noneMatch(l -> l.issueKey() != null && l.pending() && !l.closed())) {
            throw new Invalid("There is nothing to push", "plan");
        }
        if (!c.view().problems().isEmpty()) {
            throw new Invalid(c.view().problems().getFirst(), "plan");
        }
        if (!c.view().pushBlockers().isEmpty()) {
            throw new Invalid(c.view().pushBlockers().getFirst(), "plan");
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
                        start, end, shown.totalH(), labels));
            }
        }
        return out;
    }

    /** The BAL reports the Create step, so the function's BAL is read back from Jira; the other steps keep Jira's default. */
    private static @Nullable String reporter(PlanRows.RowStep st, EstimationRow r) {
        return st.kind() == StepKind.CREATE ? r.balId() : null;
    }

    /** Changes one sub-task of the plan: its effort or whether it is in the plan. */
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
        estimation.saveStepEdit(new StepEdit(row.id(), k, effort, null, null, in.removed()));
        return view(scope, epic);
    }

    /** How far from today a cell can be typed outside the plan's window. */
    static final int MAX_DAYS_AHEAD = 366;

    /**
     * Types hours into cells (a day's hours replace the cell's; 0 clears it, at most 24): a day of the window, or from today to
     * {@value #MAX_DAYS_AHEAD} days on. A cell of a sub-task in Jira must be today or later and is kept until the push; the first
     * one of a sub-task keeps Jira's plan from today on besides.
     */
    @Transactional
    public DetailPlanView saveCells(AnkenScope scope, String epic, List<CellIn> in) {
        DetailPlanView v = view(scope, epic);
        String today = EstimationService.load(context, scope, epic).today;
        String lastDay = Dates.addDays(today, MAX_DAYS_AHEAD);
        Set<String> window = new HashSet<>(v.window());
        Map<String, MatrixLine> lineOf = new HashMap<>();
        v.lines().forEach(l -> lineOf.put(l.ref(), l));
        List<Cell> rowCells = new ArrayList<>();
        Map<String, List<Cell>> jira = new LinkedHashMap<>();
        for (CellIn c : in) {
            MatrixLine l = lineOf.get(c.ref());
            if (l == null || l.removed() || l.closed()) {
                throw new Invalid(c.ref() + " is not in the plan", "ref");
            }
            if (!window.contains(c.day()) && !(isDay(c.day()) && c.day().compareTo(today) >= 0 && c.day().compareTo(lastDay) <= 0)) {
                throw new Invalid(c.day() + " is outside the plan", "day");
            }
            if (c.hours() > 24) {
                throw new Invalid("At most 24 hours a day", "hours");
            }
            if (!(c.hours() >= 0)) {
                throw new Invalid("Hours must be 0 or more", "hours");
            }
            Cell cell = new Cell(c.ref(), c.day(), c.hours(), false);
            if (l.issueKey() == null) {
                rowCells.add(cell);
            } else if (c.day().compareTo(today) < 0) {
                throw new Invalid("Days before today are locked", "day");
            } else {
                jira.computeIfAbsent(c.ref(), k -> new ArrayList<>()).add(cell);
            }
        }
        Map<String, List<Cell>> pending = cells.jiraCells(epic);
        jira.forEach((key, typed) -> {
            seed(epic, lineOf.get(key), pending, today);
            cells.saveJiraCells(epic, typed);
        });
        cells.saveRowCells(rowCells);
        return view(scope, epic);
    }

    /**
     * Moves the hours of lines from today on {@code days} working days of each line's PIC (earlier when negative), as typed cells;
     * the days before today stay. Refused as a whole when a day would land before today or past today + {@value #MAX_DAYS_AHEAD}.
     */
    @Transactional
    public DetailPlanView shift(AnkenScope scope, String epic, ShiftIn in) {
        if (in.days() == 0) {
            throw new Invalid("Shift by 1 day or more", "days");
        }
        DetailPlanView v = view(scope, epic);
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        String today = ctx.today;
        String lastDay = Dates.addDays(today, MAX_DAYS_AHEAD);
        Map<String, MatrixLine> lineOf = new HashMap<>();
        v.lines().forEach(l -> lineOf.put(l.ref(), l));
        List<Cell> rowCells = new ArrayList<>();
        Map<String, List<Cell>> jira = new LinkedHashMap<>();
        for (String ref : new LinkedHashSet<>(in.refs())) {
            MatrixLine l = lineOf.get(ref);
            if (l == null || l.removed() || l.closed()) {
                throw new Invalid(ref + " is not in the plan", "ref");
            }
            String pic = picOf(l, v.rows());
            Map<String, Double> moved = new TreeMap<>();
            for (DayHours c : l.cells()) {
                if (c.day().compareTo(today) < 0 || c.hours() <= 0) {
                    continue;
                }
                String to = Scheduling.shiftWorkDays(ctx.cal, pic, c.day(), in.days());
                if (to.compareTo(today) < 0) {
                    throw new Invalid(l.function() + " would move before today", "days");
                }
                if (to.compareTo(lastDay) > 0) {
                    throw new Invalid(l.function() + " would move beyond " + lastDay, "days");
                }
                moved.putIfAbsent(c.day(), 0.0);
                moved.merge(to, c.hours(), (a, b) -> a + b);
            }
            if (moved.values().stream().anyMatch(h -> h > 24)) {
                throw new Invalid("At most 24 hours a day", "days");
            }
            List<Cell> out = moved.entrySet().stream().map(e -> new Cell(ref, e.getKey(), e.getValue(), false)).toList();
            if (l.issueKey() == null) {
                rowCells.addAll(out);
            } else {
                jira.put(ref, out);
            }
        }
        Map<String, List<Cell>> pending = cells.jiraCells(epic);
        jira.forEach((key, out) -> {
            seed(epic, lineOf.get(key), pending, today);
            cells.saveJiraCells(epic, out);
        });
        cells.saveRowCells(rowCells);
        return view(scope, epic);
    }

    /** The PIC whose calendar a line follows: for a row's step, the person in its slot (JM Review: the JM), as the matrix shows it. */
    private static @Nullable String picOf(MatrixLine l, List<EstimationRow> rows) {
        EstimationRow r = l.issueKey() != null || l.rowId() == null ? null
                : rows.stream().filter(x -> x.id() == l.rowId()).findFirst().orElse(null);
        if (r == null || l.picSlot() == null) {
            return l.picId();
        }
        return switch (l.picSlot()) {
            case "jm" -> r.jmId();
            case "bal" -> r.balId();
            default -> r.baId();
        };
    }

    /** Whether {@code s} is a day as YYYY-MM-DD. */
    private static boolean isDay(String s) {
        try {
            return java.time.LocalDate.parse(s).toString().equals(s);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Before the first pending cell of a sub-task in Jira: keeps its plan from today on as pending cells (typed), else writing
     * one day would wipe the rest of Jira's plan.
     */
    private void seed(String epic, MatrixLine l, Map<String, List<Cell>> pending, String today) {
        String key = l.ref();
        if (pending.getOrDefault(key, List.of()).isEmpty()) {
            cells.saveJiraCells(epic, l.cells().stream().filter(d -> d.day().compareTo(today) >= 0)
                    .map(d -> new Cell(key, d.day(), d.hours(), false)).toList());
        }
    }

    /** Changes the Total (Spent + Remaining) and the PIC of a sub-task in Jira; kept until the push. */
    @Transactional
    public DetailPlanView editJiraLine(AnkenScope scope, String epic, String key, JiraLineIn in) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        MatrixLine l = view(scope, epic).lines().stream().filter(x -> key.equals(x.issueKey())).findFirst()
                .orElseThrow(() -> new Invalid(key + " is not in the plan", "key"));
        if (l.closed()) {
            throw new Invalid(key + " is not in the plan", "key");
        }
        Double total = in.totalH();
        if (total != null && total < l.spentH() - 1e-9) {
            throw new Invalid("Total is below the hours already logged", "total_h");
        }
        String pic = blank(in.picId());
        if (pic != null && !ctx.isMember(epic, pic)) {
            throw new Invalid(ctx.notAMember(epic, pic), "pic_id");
        }
        JiraEdit was = cells.jiraEdits(epic).stream().filter(e -> e.issueKey().equals(key)).findFirst().orElse(null);
        Issue issue = ctx.subtasks().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
        Double remaining = was == null ? null : was.remainingH();
        if (total != null) {
            double wanted = Numbers.hours(total - l.spentH());
            remaining = Math.abs(wanted - issue.remainingH()) < 0.01 ? null : wanted;
        }
        String assignee = pic != null ? (pic.equals(issue.assigneeId()) ? null : pic) : was == null ? null : was.assigneeId();
        cells.saveJiraEdit(epic, new JiraEdit(key, remaining, assignee));
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
