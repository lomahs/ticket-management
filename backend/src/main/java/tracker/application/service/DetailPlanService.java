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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.Invalid;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.MasterScheduleRepository;
import tracker.domain.Allocations;
import tracker.domain.AnkenScope;
import tracker.domain.Capacity;
import tracker.domain.Dates;
import tracker.domain.DetailScheduler;
import tracker.domain.DetailScheduler.Chain;
import tracker.domain.DetailScheduler.DayOverrun;
import tracker.domain.DetailScheduler.Overrun;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.DetailScheduler.Step;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.PhaseGroups;
import tracker.domain.Role;
import tracker.domain.RuleError;
import tracker.domain.ScheduleVersion;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.StepWeights;
import tracker.domain.Steps;
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
    private final Clock clock;

    public DetailPlanService(TrackerContextLoader context, EstimationRepository estimation, MasterScheduleRepository versions,
                             AllocationService allocations, TicketService tickets, Clock clock) {
        this.context = context;
        this.estimation = estimation;
        this.versions = versions;
        this.allocations = allocations;
        this.tickets = tickets;
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

    /** @param problems what keeps a plan from being computed; when any, {@code plan} is empty */
    public record DetailPlanView(List<EstimationRow> rows, List<EstimationService.TaskOption> tasks, List<RowPlan> plan,
                                 List<Overrun> overBudget, List<DayOverrun> overDay, List<PhaseCheck> checks, List<String> problems) {
    }

    /** The computed plan of an anken's unpushed rows, with what it needs to be pushed. */
    record Computed(TrackerContext ctx, @Nullable ScheduleVersion schedule, List<EstimationRow> rows, List<Placed> placed, DetailPlanView view) {
    }

    public DetailPlanView view(AnkenScope scope, String epic) {
        return compute(scope, epic).view();
    }

    /** Saves the order of the unpushed rows and their people; returns the plan that follows. */
    public DetailPlanView saveDraft(AnkenScope scope, String epic, DraftIn draft) {
        EstimationService.load(context, scope, epic);
        List<EstimationRow> all = estimation.findByEpic(epic);
        List<EstimationRow> open = all.stream().filter(r -> !r.pushed()).toList();
        Map<Long, RowAssign> assign = new HashMap<>();
        draft.assigns().forEach(a -> assign.put(a.rowId(), a));
        List<EstimationRow> ordered = new ArrayList<>();
        draft.order().forEach(id -> open.stream().filter(r -> r.id() == id).findFirst().ifPresent(ordered::add));
        open.stream().filter(r -> !ordered.contains(r)).forEach(ordered::add);
        int position = all.size() - open.size();
        List<EstimationRow> next = new ArrayList<>();
        for (EstimationRow r : ordered) {
            RowAssign a = assign.get(r.id());
            next.add(new EstimationRow(r.id(), epic, position++, r.phase(), r.subphase(), r.functionName(), r.steps(), r.effortH(),
                    a != null ? blank(a.baId()) : r.baId(), a != null ? blank(a.balId()) : r.balId(), a != null ? blank(a.jmId()) : r.jmId(),
                    r.taskKey(), null));
        }
        estimation.save(epic, next);
        return view(scope, epic);
    }

    private static @Nullable String blank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    Computed compute(AnkenScope scope, String epic) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        List<EstimationRow> all = estimation.findByEpic(epic);
        List<EstimationRow> rows = all.stream().filter(r -> !r.pushed()).toList();
        List<ScheduleVersion> saved = versions.findByEpic(epic);
        ScheduleVersion schedule = saved.isEmpty() ? null : saved.getLast();
        List<EstimationService.TaskOption> tasks = EstimationService.tasks(ctx, epic);
        List<String> problems = problems(ctx, epic, schedule, rows, tasks);
        List<Placed> placed = List.of();
        List<Overrun> over = List.of();
        List<DayOverrun> overDay = List.of();
        if (problems.isEmpty() && !rows.isEmpty()) {
            Map<String, PhaseRow> phases = phases(schedule);
            Allocations a = allocations.counted(scope).allocations();
            StepWeights w = ctx.settings.stepWeightsOf(epic);
            List<Chain> chains = rows.stream().map(r -> chain(r, w, phases.get(r.phase()).start())).toList();
            try {
                DetailScheduler.Result res = DetailScheduler.schedule(epic, chains, loads(ctx, epic, Set.of()), a, ctx.cal,
                        ctx.settings.workingHours.hoursPerDay());
                placed = res.placed();
                overDay = res.overDay();
                over = DetailScheduler.overBudget(placed, givenBefore(ctx, epic, Set.of()), phases, epic, a, ctx.cal);
            } catch (RuleError e) {
                problems = List.of(e.getMessage());
            }
        }
        List<RowPlan> plan = new ArrayList<>();
        for (EstimationRow r : rows) {
            List<StepPlan> steps = placed.stream().filter(p -> rowOf(p.ref()) == r.id())
                    .map(p -> new StepPlan(p.ref(), p.kind() == null ? null : p.kind().value(), p.memberId(), p.hours(), p.start(), p.end())).toList();
            if (!steps.isEmpty()) {
                plan.add(new RowPlan(r.id(), steps));
            }
        }
        List<PhaseCheck> checks = schedule == null ? List.of() : checks(schedule, placed);
        DetailPlanView view = new DetailPlanView(all, tasks, plan, over, overDay, checks, problems);
        return new Computed(ctx, schedule, rows, placed, view);
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

    /** The steps of a row: one per step of a function (people by the step's role), or the row itself. */
    private static Chain chain(EstimationRow r, StepWeights w, String earliest) {
        if (r.steps() == null) {
            return new Chain(earliest, List.of(new Step(String.valueOf(r.id()), r.phase(), null, Objects.requireNonNull(r.baId()), r.effortH(), null)));
        }
        List<Step> steps = new ArrayList<>();
        for (StepKind k : StepWeights.stepsOf(r.steps())) {
            Role role = Steps.STEPS.get(Steps.index(k)).role();
            String who = role == Role.BAL ? r.balId() : role == Role.JM ? r.jmId() : r.baId();
            steps.add(new Step(r.id() + ":" + k.value(), r.phase(), k, Objects.requireNonNull(who), w.stepHours(r.effortH(), k), null));
        }
        return new Chain(earliest, steps);
    }

    private static List<String> problems(TrackerContext ctx, String epic, @Nullable ScheduleVersion schedule, List<EstimationRow> rows,
                                         List<EstimationService.TaskOption> tasks) {
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
            person(ctx, epic, out, row, r.balId(), "a BAL");
            if (r.steps() == Steps.COUNT) {
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
        List<Capacity.PlanItem> items = new ArrayList<>();
        for (Issue i : openSubtasks(ctx, epic, except)) {
            String who = ctx.planOwner(i);
            if (who != null && Text.hasText(i.plannedStart()) && Text.hasText(i.plannedEnd())) {
                items.add(new Capacity.PlanItem(i.key(), who, i.plannedStart(), i.plannedEnd(), i.estimateH()));
            }
        }
        Capacity.Plan plan = Capacity.dailyPlan(items, ctx.cal);
        Map<String, Map<String, Double>> out = new HashMap<>();
        for (String m : ctx.people) {
            plan.member(m).forEach((d, dp) -> out.computeIfAbsent(m, k -> new TreeMap<>()).put(d, dp.total));
        }
        return out;
    }

    /** "member|phase" → estimate the member already has in the phase in Jira (JM Review aside). */
    static Map<String, Double> givenBefore(TrackerContext ctx, String epic, Set<String> except) {
        Map<String, String> groups = groupOfTask(ctx, epic);
        Map<String, Double> out = new HashMap<>();
        for (Issue i : ctx.subtasks()) {
            String who = ctx.planOwner(i);
            if (i.epicKey().equals(epic) && who != null && !except.contains(i.key())) {
                out.merge(who + "|" + groups.getOrDefault(Text.orElse(i.parentKey(), ""), PhaseGroups.OTHER), i.estimateH(), Double::sum);
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

    /** The review of the push: every sub-task, with the request that creates it. */
    public BatchPreview pushPreview(AnkenScope scope, String epic) {
        Computed c = pushable(scope, epic);
        return tickets.previewBatch(scope, epic, List.of(), newTickets(c, epic));
    }

    /**
     * Creates the sub-tasks of the unpushed rows under their Tasks; a row is marked pushed once all its sub-tasks exist, also
     * when Jira refuses a request half-way.
     */
    public BatchResult push(Viewer actor, AnkenScope scope, String epic) {
        Computed c = pushable(scope, epic);
        List<NewTicket> creates = newTickets(c, epic);
        return tickets.applyBatch(actor, scope, epic, List.of(), creates, created -> {
            List<Long> done = new ArrayList<>();
            for (EstimationRow r : c.rows()) {
                List<String> refs = c.placed().stream().map(Placed::ref).filter(ref -> rowOf(ref) == r.id()).toList();
                if (!refs.isEmpty() && created.keySet().containsAll(refs)) {
                    done.add(r.id());
                }
            }
            if (!done.isEmpty()) {
                estimation.markPushed(done, Instant.now(clock).toString());
            }
        });
    }

    /** The computed plan, refused when it cannot be pushed as it is. */
    private Computed pushable(AnkenScope scope, String epic) {
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
        if (!v.overDay().isEmpty()) {
            DayOverrun o = v.overDay().getFirst();
            throw new Invalid(c.ctx().members.get(o.memberId()).name() + " has " + o.plannedH() + "h planned on " + o.date() + ", more than the "
                    + o.allocationH() + "h allocation", "plan");
        }
        return c;
    }

    /** Each step's sub-task under its row's Task. */
    private static List<NewTicket> newTickets(Computed c, String epic) {
        TrackerContext ctx = c.ctx();
        List<NewTicket> out = new ArrayList<>();
        Map<String, ParentOption> parents = new HashMap<>();
        AnkenService.parentOptions(ctx, epic).forEach(p -> parents.put(p.key(), p));
        boolean ids = AnkenService.ruleHasId(ctx, epic);
        Map<String, String> lastId = new HashMap<>();
        for (EstimationRow r : c.rows()) {
            String parentKey = Objects.requireNonNull(r.taskKey());
            ParentOption p = parents.get(parentKey);
            // the phase and prefix of the names: the Task's, else its phase group, else the row's phase
            String phase = Text.hasText(p.phase()) ? p.phase() : !PhaseGroups.OTHER.equals(p.group()) ? p.group() : r.phase();
            String prefix = Text.hasText(p.prefix()) ? p.prefix() : phase;
            String fid = "";
            if (r.steps() != null && ids) {
                String prev = lastId.get(parentKey);
                fid = prev == null ? AnkenService.nextFunctionId(ctx.functions.stream().filter(f -> f.parentKey().equals(parentKey)).toList())
                        : nextId(prev);
                lastId.put(parentKey, fid);
            }
            for (Placed s : c.placed()) {
                if (rowOf(s.ref()) == r.id()) {
                    out.add(new NewTicket(s.ref(), true, parentKey, null, AnkenService.subtaskSummary(ctx, epic, phase, prefix, fid,
                            r.functionName(), s.kind()), s.memberId(), null, s.start(), s.end(), s.hours()));
                }
            }
        }
        return out;
    }

    /** The id after {@code id}: its trailing number plus one, as wide as before ("F09" → "F10"). */
    static String nextId(String id) {
        int i = id.length();
        while (i > 0 && Character.isDigit(id.charAt(i - 1))) {
            i--;
        }
        if (i == id.length()) {
            return id + "2";
        }
        String num = id.substring(i);
        return id.substring(0, i) + String.format("%0" + num.length() + "d", Long.parseLong(num) + 1);
    }
}
