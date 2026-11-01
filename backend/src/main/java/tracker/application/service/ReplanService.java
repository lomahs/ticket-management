package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.Invalid;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.service.DetailPlanService.PhaseCheck;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.TicketService.ExpectedChange;
import tracker.application.service.TicketService.ExpectedUpdate;
import tracker.application.service.TicketService.TicketChanges;
import tracker.application.service.TicketService.TicketUpdate;
import tracker.domain.Allocations;
import tracker.domain.AnkenScope;
import tracker.domain.Dates;
import tracker.domain.DetailScheduler;
import tracker.domain.DetailScheduler.Chain;
import tracker.domain.DetailScheduler.DayOverrun;
import tracker.domain.DetailScheduler.Overrun;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.DetailScheduler.Step;
import tracker.domain.Issue;
import tracker.domain.PhaseGroups;
import tracker.domain.RuleError;
import tracker.domain.ScheduleVersion;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Steps;
import tracker.domain.Text;
import tracker.domain.TicketField;
import tracker.domain.Viewer;

/**
 * Re-plans a phase from what Jira has: every sub-task of the phase except the Closed ones gets new dates (and, when the PIM
 * says so, a new assignee) by the same rules as the detail plan; the changes go to Jira through the reviewed batch.
 */
@Service
public class ReplanService {
    private final TrackerContextLoader context;
    private final MasterScheduleRepository versions;
    private final AllocationService allocations;
    private final TicketService tickets;

    public ReplanService(TrackerContextLoader context, MasterScheduleRepository versions, AllocationService allocations, TicketService tickets) {
        this.context = context;
        this.versions = versions;
        this.allocations = allocations;
        this.tickets = tickets;
    }

    /** @param assignees sub-task key → the member the PIM gives it to */
    public record ReplanIn(String phase, Map<String, String> assignees) {
    }

    /** @param started WIP or Resolved: keeps its Planned Start, its remaining estimate is placed */
    public record ReplanItem(String key, String summary, @Nullable String step, @Nullable String fromMember, String toMember,
                             @Nullable String fromStart, @Nullable String fromEnd, String toStart, String toEnd, double hours, boolean started) {
    }

    public record ReplanView(List<ReplanItem> items, List<Overrun> overBudget, List<DayOverrun> overDay, @Nullable PhaseCheck check,
                             List<String> problems) {
    }

    public ReplanView compute(AnkenScope scope, String epic, ReplanIn in) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        List<ScheduleVersion> saved = versions.findByEpic(epic);
        PhaseRow phase = saved.isEmpty() ? null : DetailPlanService.phases(saved.getLast()).get(in.phase());
        if (phase == null) {
            return new ReplanView(List.of(), List.of(), List.of(), null,
                    List.of(saved.isEmpty() ? "Save a master schedule first" : in.phase() + " is not in the master schedule"));
        }
        Map<String, String> groups = DetailPlanService.groupOfTask(ctx, epic);
        List<Issue> items = DetailPlanService.openSubtasks(ctx, epic, Set.of()).stream()
                .filter(i -> in.phase().equals(groups.getOrDefault(Text.orElse(i.parentKey(), ""), PhaseGroups.OTHER))).toList();
        List<String> problems = new ArrayList<>();
        Map<String, String> to = new HashMap<>();
        for (Issue i : items) {
            String who = in.assignees().getOrDefault(i.key(), i.assigneeId());
            if (!Text.hasText(who)) {
                problems.add(i.key() + " has no assignee");
            } else if (!ctx.isMember(epic, who)) {
                problems.add(i.key() + ": " + ctx.notAMember(epic, who));
            } else {
                to.put(i.key(), who);
            }
        }
        if (!problems.isEmpty()) {
            return new ReplanView(List.of(), List.of(), List.of(), null, problems);
        }
        String earliest = Dates.max(phase.start(), ctx.today);
        List<Chain> chains = chains(items, to, in.phase(), earliest, ctx.today);
        Set<String> keys = items.stream().map(Issue::key).collect(Collectors.toSet());
        Allocations a = allocations.counted(scope).allocations();
        try {
            DetailScheduler.Result res = DetailScheduler.schedule(epic, chains, DetailPlanService.loads(ctx, epic, keys), a, ctx.cal,
                    ctx.settings.workingHours.hoursPerDay());
            Map<String, Double> before = DetailPlanService.givenBefore(ctx, epic, keys);
            Map<String, Issue> byKey = new HashMap<>();
            items.forEach(i -> byKey.put(i.key(), i));
            for (Placed p : res.placed()) {
                Issue i = byKey.get(p.ref());
                if (started(i) && p.kind() != StepKind.JM_REVIEW) { // the part already done still counts in the phase
                    before.merge(p.memberId() + "|" + p.phase(), Math.max(0, i.estimateH() - p.hours()), Double::sum);
                }
            }
            List<Overrun> over = DetailScheduler.overBudget(res.placed(), before, Map.of(in.phase(), phase), epic, a, ctx.cal);
            List<ReplanItem> out = res.placed().stream().map(p -> {
                Issue i = byKey.get(p.ref());
                return new ReplanItem(i.key(), i.summary(), p.kind() == null ? null : p.kind().value(), i.assigneeId(), p.memberId(),
                        i.plannedStart(), i.plannedEnd(), p.start(), p.end(), p.hours(), started(i));
            }).toList();
            String end = res.placed().stream().map(Placed::end).max(String::compareTo).orElse(null);
            PhaseCheck check = new PhaseCheck(in.phase(), phase.end(), end, end == null ? 0 : Dates.dayDiff(phase.end(), end));
            return new ReplanView(out, over, res.overDay(), check, List.of());
        } catch (RuleError e) {
            return new ReplanView(List.of(), List.of(), List.of(), null, List.of(e.getMessage()));
        }
    }

    private static boolean started(Issue i) {
        return i.status() == Status.WIP || i.status() == Status.RESOLVED;
    }

    /** A function's steps in step order, or a ticket alone; ordered by current Planned Start (none last), then key. */
    private static List<Chain> chains(List<Issue> items, Map<String, String> to, String phase, String earliest, String today) {
        Map<String, List<Issue>> byFunction = new LinkedHashMap<>();
        for (Issue i : items) {
            boolean step = Steps.stepDef(i) != null;
            byFunction.computeIfAbsent(step ? i.parentKey() + "|" + i.functionId() : i.key(), k -> new ArrayList<>()).add(i);
        }
        List<List<Issue>> groups = new ArrayList<>(byFunction.values());
        groups.forEach(g -> g.sort(Comparator.comparingInt((Issue i) -> Steps.stepDef(i) == null ? 0 : Steps.index(i.step()))));
        groups.sort(Comparator.comparing((List<Issue> g) -> g.stream().map(Issue::plannedStart).filter(Text::hasText).min(String::compareTo)
                .orElse("9999")).thenComparing(g -> g.stream().map(Issue::key).min(String::compareTo).orElseThrow()));
        List<Chain> out = new ArrayList<>();
        for (List<Issue> g : groups) {
            List<Step> steps = new ArrayList<>();
            for (Issue i : g) {
                StepKind kind = Steps.stepDef(i) == null ? null : i.step();
                boolean started = started(i);
                double hours = started ? i.remainingH() : i.estimateH();
                String fixed = started ? Text.orElse(i.plannedStart(), today) : null;
                steps.add(new Step(i.key(), phase, kind, Objects.requireNonNull(to.get(i.key())), hours, fixed));
            }
            out.add(new Chain(earliest, steps));
        }
        return out;
    }

    /** The items whose dates or assignee move, as the batch's updates. */
    private static List<TicketUpdate> updates(ReplanView v) {
        List<TicketUpdate> out = new ArrayList<>();
        for (ReplanItem i : v.items()) {
            boolean member = !Objects.equals(i.fromMember(), i.toMember());
            boolean start = !Objects.equals(i.fromStart(), i.toStart());
            boolean end = !Objects.equals(i.fromEnd(), i.toEnd());
            if (member || start || end) {
                out.add(new TicketUpdate(i.key(), new TicketChanges(null, member ? i.toMember() : null, null, start ? i.toStart() : null,
                        end ? i.toEnd() : null, null)));
            }
        }
        return out;
    }

    private ReplanView applicable(AnkenScope scope, String epic, ReplanIn in) {
        ReplanView v = compute(scope, epic, in);
        if (!v.problems().isEmpty()) {
            throw new Invalid(v.problems().getFirst(), "plan");
        }
        if (!v.overBudget().isEmpty() || !v.overDay().isEmpty()) {
            throw new Invalid("The re-plan goes over a member's budget or allocation", "plan");
        }
        if (updates(v).isEmpty()) {
            throw new Invalid("Nothing moves", "plan");
        }
        return v;
    }

    /** The review of the changes: each moved sub-task with what Jira has now and the requests. */
    public BatchPreview preview(AnkenScope scope, String epic, ReplanIn in) {
        ReplanView v = applicable(scope, epic, in);
        return tickets.previewBatch(scope, epic, updates(v), List.of());
    }

    /** Sends a reviewed re-plan; refused when the re-plan computed now differs from the one reviewed. */
    public BatchResult apply(Viewer actor, AnkenScope scope, String epic, ReplanIn in, List<ExpectedUpdate> reviewed) {
        ReplanView v = applicable(scope, epic, in);
        Map<String, Map<TicketField, String>> want = new HashMap<>();
        for (TicketUpdate u : updates(v)) {
            Map<TicketField, String> f = new HashMap<>();
            if (u.changes().assigneeId() != null) {
                f.put(TicketField.ASSIGNEE, u.changes().assigneeId());
            }
            if (u.changes().plannedStart() != null) {
                f.put(TicketField.PLANNED_START, u.changes().plannedStart());
            }
            if (u.changes().plannedEnd() != null) {
                f.put(TicketField.PLANNED_END, u.changes().plannedEnd());
            }
            want.put(u.key(), f);
        }
        Map<String, Map<TicketField, String>> got = new HashMap<>();
        for (ExpectedUpdate u : reviewed) {
            Map<TicketField, String> f = new HashMap<>();
            for (ExpectedChange c : u.changes()) {
                f.put(c.field(), c.after());
            }
            got.put(u.key(), f);
        }
        // the review drops fields Jira already has, so each reviewed value must be the re-plan's, and every reviewed key one it moves
        boolean same = want.keySet().containsAll(got.keySet()) && got.entrySet().stream()
                .allMatch(e -> e.getValue().entrySet().stream().allMatch(f -> Objects.equals(want.get(e.getKey()).get(f.getKey()), f.getValue())));
        if (!same) {
            throw new Invalid("The re-plan changed since the review; review again", "updates");
        }
        return tickets.applyBatch(actor, scope, epic, reviewed, List.of());
    }
}
