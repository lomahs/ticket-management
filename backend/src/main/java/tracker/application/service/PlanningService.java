package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.SyncService;
import tracker.application.TaskCard;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.JiraWriter;
import tracker.domain.Allocations;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.Capacity.PlanItem;
import tracker.domain.Capacity.WeekLoad;
import tracker.domain.Capacity;
import tracker.domain.DateReason;
import tracker.domain.Dates;
import tracker.domain.Holiday;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.Leave;
import tracker.domain.LeaveTypeInfo;
import tracker.domain.Scheduling;
import tracker.domain.StepKind;
import tracker.domain.Steps;
import tracker.domain.Text;

/** Planning: the Gantt by member, previews of pending moves (dates, reassignment, capacity) and pushing them to Jira. */
@Service
public class PlanningService {
    private final TrackerContextLoader context;
    private final Optional<JiraWriter> writer;
    private final SyncService sync;
    private final AllocationRepository allocations;

    public PlanningService(TrackerContextLoader context, Optional<JiraWriter> writer, SyncService sync,
                           AllocationRepository allocations) {
        this.allocations = allocations;
        this.context = context;
        this.writer = writer;
        this.sync = sync;
    }

    public static final int CAPACITY_WEEKS = 5;

    public record DayHead(String date, boolean isToday, @Nullable String extraNote) {
    }

    /** @param kind holiday | leave | leave_half | null */
    public record DayCell(String date, @Nullable String kind, @Nullable String label, boolean tentative) {
    }

    /** @param colorStep step kind used for the block colour, or "other" */
    public record PlanBlock(TaskCard card, String colorStep) {
    }

    public record MemberLane(String memberId, List<DayCell> days, List<PlanBlock> items) {
    }

    public record MemberCapacity(String memberId, List<WeekLoad> weeks) {
    }

    public record PlanningView(String start, String end, List<DayHead> days, List<String> projects, List<MemberLane> rows,
                               List<MemberCapacity> capacity) {
    }

    public record PlanChange(String key, String start, String memberId) {
    }

    public record ChangePreview(String key, String fromStart, String fromEnd, @Nullable String fromMember, String toStart, String toEnd,
                                String toMember, List<DateReason> dayOff) {
    }

    public record PlanningPreview(List<ChangePreview> changes, List<MemberCapacity> capacity) {
    }

    private static DayCell dayCell(TrackerContext ctx, String mid, String day) {
        Holiday h = ctx.cal.holidayFor(mid, day);
        if (h != null) {
            return new DayCell(day, "holiday", h.name(), false);
        }
        Leave lv = ctx.cal.leave(mid, day);
        if (lv != null) {
            LeaveTypeInfo info = LeaveTypeInfo.of(lv.type());
            return new DayCell(day, info.full() ? "leave" : "leave_half", info.label(), info.tentative());
        }
        return new DayCell(day, null, null, false);
    }

    /** Sub-tasks with both planned dates. */
    private static List<Issue> planned(TrackerContext ctx) {
        return ctx.issues.stream().filter(i -> i.kind() == IssueKind.SUBTASK && Text.hasText(i.plannedStart())
                && Text.hasText(i.plannedEnd())).toList();
    }

    private List<MemberCapacity> capacity(TrackerContext ctx, AnkenScope scope, String start, Map<String, ChangePreview> moved) {
        List<PlanItem> items = new ArrayList<>();
        for (Issue i : planned(ctx)) {
            ChangePreview c = moved.get(i.key());
            if (c != null) {
                items.add(new PlanItem(i.key(), c.toMember(), c.toStart(), c.toEnd(), i.estimateH()));
            } else {
                String worker = ctx.worker(i);
                if (Text.hasText(worker)) {
                    items.add(new PlanItem(i.key(), worker, i.plannedStart(), i.plannedEnd(), i.estimateH()));
                }
            }
        }
        Set<String> scopeEpics = new HashSet<>(ctx.settings.jira.epics);
        scopeEpics.retainAll(scope.epics());
        List<String> memberIds = ctx.people;
        Map<String, List<WeekLoad>> loads = Capacity.weeklyCapacity(items, ctx.cal, memberIds, Dates.mondayOf(start),
                new Allocations(allocations.findAll()), scopeEpics, ctx.settings.thresholds.capacityPct(), CAPACITY_WEEKS);
        return memberIds.stream().map(m -> new MemberCapacity(m, loads.get(m))).toList();
    }

    /** Lanes and capacity rows are the people of the scope. */
    public PlanningView view(AnkenScope scope, String start, int weeks) {
        if (weeks != 1 && weeks != 2) {
            throw new Invalid("weeks must be 1 or 2", "weeks");
        }
        TrackerContext ctx = context.load(scope);
        String first = Dates.mondayOf(start);
        String last = Dates.addDays(first, weeks * 7L - 1);
        List<String> days = new ArrayList<>();
        for (int n = 0; n < weeks * 7; n++) {
            String x = Dates.addDays(first, n);
            if (ctx.cal.visible(x)) {
                days.add(x);
            }
        }
        List<MemberLane> rows = new ArrayList<>();
        for (String mid : ctx.people) {
            List<Issue> items = new ArrayList<>(planned(ctx).stream().filter(i -> Objects.equals(ctx.worker(i), mid)
                    && i.plannedEnd().compareTo(first) >= 0 && i.plannedStart().compareTo(last) <= 0).toList());
            items.sort(Comparator.comparing(Issue::plannedStart).thenComparing(Issue::plannedEnd).thenComparing(Issue::key));
            List<PlanBlock> blocks = items.stream().map(i -> {
                StepKind k = i.step() != null ? i.step() : Steps.stepHint(i.summary());
                return new PlanBlock(ctx.card(i), k != null ? k.value() : "other");
            }).toList();
            rows.add(new MemberLane(mid, days.stream().map(x -> dayCell(ctx, mid, x)).toList(), blocks));
        }
        List<DayHead> heads = days.stream().map(x -> new DayHead(x, x.equals(ctx.today), ctx.cal.extraDays().get(x))).toList();
        List<String> projects = List.copyOf(new TreeSet<>(ctx.anken.stream().map(Anken::project).toList()));
        return new PlanningView(first, last, heads, projects, rows, capacity(ctx, scope, first, Map.of()));
    }

    /** Every key must be in the scope (else not found); a new worker must be a member of the ticket's anken. */
    private static List<ChangePreview> previews(TrackerContext ctx, List<PlanChange> changes) {
        List<ChangePreview> out = new ArrayList<>();
        for (int n = 0; n < changes.size(); n++) {
            PlanChange ch = changes.get(n);
            Issue i = ctx.issue(ch.key()).orElseThrow(() -> new NotFound("Ticket " + ch.key() + " was not found", "key"));
            if (!Text.hasText(i.plannedStart()) || !Text.hasText(i.plannedEnd())) {
                throw new Invalid(ch.key() + " has no plan to move", "key");
            }
            if (!ctx.members.containsKey(ch.memberId())) {
                throw new Invalid("Unknown member " + ch.memberId(), "member_id");
            }
            if (!Objects.equals(ch.memberId(), ctx.worker(i)) && !ctx.isMember(i.epicKey(), ch.memberId())) {
                throw new Invalid(ctx.notAMember(i.epicKey(), ch.memberId()), "changes." + n + ".member_id");
            }
            String[] to = Scheduling.shiftPlan(i.plannedStart(), i.plannedEnd(), ch.start());
            List<DateReason> offs = Scheduling.daysOff(ctx.cal, ch.memberId(), to[0], to[1]);
            out.add(new ChangePreview(i.key(), i.plannedStart(), i.plannedEnd(), ctx.worker(i), to[0], to[1], ch.memberId(), offs));
        }
        return out;
    }

    public PlanningPreview preview(AnkenScope scope, String start, List<PlanChange> changes) {
        TrackerContext ctx = context.load(scope);
        List<ChangePreview> pv = previews(ctx, changes);
        Map<String, ChangePreview> moved = new HashMap<>();
        pv.forEach(c -> moved.put(c.key(), c));
        return new PlanningPreview(pv, capacity(ctx, scope, start, moved));
    }

    /** Writes the moves to Jira and reads the moved issues back. */
    public List<String> push(AnkenScope scope, List<PlanChange> changes) {
        JiraWriter jira = writer.orElseThrow(() -> new CapabilityDisabled("Writing to Jira is not available yet"));
        TrackerContext ctx = context.load(scope);
        List<ChangePreview> pv = previews(ctx, changes);
        for (ChangePreview c : pv) {
            boolean reassigned = !Objects.equals(c.toMember(), c.fromMember());
            String account = reassigned ? ctx.members.get(c.toMember()).jiraAccountId() : null;
            if (reassigned && !Text.hasText(account)) {
                throw new Invalid(ctx.members.get(c.toMember()).name() + " has no Jira account", "member_id");
            }
            jira.updateIssue(c.key(), c.toStart(), c.toEnd(), account);
        }
        List<String> keys = pv.stream().map(ChangePreview::key).toList();
        sync.refresh(keys);
        return keys;
    }
}
