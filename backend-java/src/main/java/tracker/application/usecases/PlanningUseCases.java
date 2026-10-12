package tracker.application.usecases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Invalid;
import tracker.application.Context;
import tracker.application.Deps;
import tracker.application.TaskCard;
import tracker.application.ports.UnitOfWork;
import tracker.domain.Anken;
import tracker.domain.Capacity;
import tracker.domain.Capacity.PlanItem;
import tracker.domain.Capacity.WeekLoad;
import tracker.domain.DateReason;
import tracker.domain.Dates;
import tracker.domain.Holiday;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.Leave;
import tracker.domain.LeaveTypeInfo;
import tracker.domain.Py;
import tracker.domain.Scheduling;
import tracker.domain.StepKind;
import tracker.domain.Steps;
import tracker.domain.WorkingHours;

/** Planning: the Gantt by member, previews of pending moves (dates, reassignment, capacity) and pushing them to Jira. */
public final class PlanningUseCases {
    private PlanningUseCases() {
    }

    public static final int CAPACITY_WEEKS = 5;

    public record DayHead(String date, boolean isToday, String extraNote) {
    }

    /** @param kind holiday | leave | leave_half | null */
    public record DayCell(String date, String kind, String label, boolean tentative) {
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

    public record ChangePreview(String key, String fromStart, String fromEnd, String fromMember, String toStart, String toEnd,
                                String toMember, List<DateReason> dayOff) {
    }

    public record PlanningPreview(List<ChangePreview> changes, List<MemberCapacity> capacity) {
    }

    private static Context ctx(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            return Context.load(u, d.clock().today());
        }
    }

    private static DayCell dayCell(Context ctx, String mid, String day) {
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
    private static List<Issue> planned(Context ctx) {
        return ctx.issues.stream().filter(i -> i.kind() == IssueKind.SUBTASK && Py.truthy(i.plannedStart())
                && Py.truthy(i.plannedEnd())).toList();
    }

    private static List<MemberCapacity> capacity(Context ctx, String start, Map<String, ChangePreview> moved) {
        List<PlanItem> items = new ArrayList<>();
        for (Issue i : planned(ctx)) {
            ChangePreview c = moved.get(i.key());
            if (c != null) {
                items.add(new PlanItem(i.key(), c.toMember(), c.toStart(), c.toEnd(), i.estimateH()));
            } else {
                String worker = ctx.worker(i);
                if (Py.truthy(worker)) {
                    items.add(new PlanItem(i.key(), worker, i.plannedStart(), i.plannedEnd(), i.estimateH()));
                }
            }
        }
        WorkingHours wh = ctx.settings.workingHours;
        List<String> memberIds = new ArrayList<>(ctx.members.keySet());
        Map<String, List<WeekLoad>> loads = Capacity.weeklyCapacity(items, ctx.cal, memberIds, Dates.mondayOf(start),
                wh.hoursPerDay(), ctx.settings.thresholds.capacityPct(), CAPACITY_WEEKS);
        return memberIds.stream().map(m -> new MemberCapacity(m, loads.get(m))).toList();
    }

    public static PlanningView view(Deps d, String start, int weeks) {
        if (weeks != 1 && weeks != 2) {
            throw new Invalid("weeks must be 1 or 2", "weeks");
        }
        Context ctx = ctx(d);
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
        for (String mid : ctx.members.keySet()) {
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
        return new PlanningView(first, last, heads, projects, rows, capacity(ctx, first, Map.of()));
    }

    private static List<ChangePreview> previews(Context ctx, List<PlanChange> changes) {
        List<ChangePreview> out = new ArrayList<>();
        for (PlanChange ch : changes) {
            Issue i = ctx.get(ch.key());
            if (i == null || !Py.truthy(i.plannedStart()) || !Py.truthy(i.plannedEnd())) {
                throw new Invalid(ch.key() + " has no plan to move", "key");
            }
            if (!ctx.members.containsKey(ch.memberId())) {
                throw new Invalid("Unknown member " + ch.memberId(), "member_id");
            }
            String[] to = Scheduling.shiftPlan(i.plannedStart(), i.plannedEnd(), ch.start());
            List<DateReason> offs = Scheduling.daysOff(ctx.cal, ch.memberId(), to[0], to[1]);
            out.add(new ChangePreview(i.key(), i.plannedStart(), i.plannedEnd(), ctx.worker(i), to[0], to[1], ch.memberId(), offs));
        }
        return out;
    }

    public static PlanningPreview preview(Deps d, String start, List<PlanChange> changes) {
        Context ctx = ctx(d);
        List<ChangePreview> pv = previews(ctx, changes);
        Map<String, ChangePreview> moved = new HashMap<>();
        pv.forEach(c -> moved.put(c.key(), c));
        return new PlanningPreview(pv, capacity(ctx, start, moved));
    }

    public static List<String> push(Deps d, List<PlanChange> changes) {
        if (d.writer() == null) {
            throw new CapabilityDisabled("Writing to Jira is not available yet");
        }
        Context ctx = ctx(d);
        List<ChangePreview> pv = previews(ctx, changes);
        for (ChangePreview c : pv) {
            boolean reassigned = !Objects.equals(c.toMember(), c.fromMember());
            String account = reassigned ? ctx.members.get(c.toMember()).jiraAccountId() : null;
            if (reassigned && !Py.truthy(account)) {
                throw new Invalid(ctx.members.get(c.toMember()).name() + " has no Jira account", "member_id");
            }
            d.writer().updateIssue(c.key(), c.toStart(), c.toEnd(), account);
        }
        List<String> keys = pv.stream().map(ChangePreview::key).toList();
        d.sync().refresh(keys);
        return keys;
    }
}
