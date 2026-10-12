package tracker.application.usecases;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.Context;
import tracker.application.Deps;
import tracker.application.ports.UnitOfWork;
import tracker.application.usecases.PlanningUseCases.DayHead;
import tracker.domain.Analysis;
import tracker.domain.Analysis.EffortBar;
import tracker.domain.Analysis.Rework;
import tracker.domain.Capacity;
import tracker.domain.Capacity.PlanItem;
import tracker.domain.Dates;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Num;
import tracker.domain.Overview;
import tracker.domain.Py;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepDef;
import tracker.domain.Steps;
import tracker.domain.Timesheet;
import tracker.domain.Timesheet.TimesheetRow;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;

/** Effort: timesheet (logged vs planned vs expected), one member-day in detail, and estimate/rework analysis. */
public final class EffortUseCases {
    private EffortUseCases() {
    }

    public static final int MAX_DAYS = 62;

    public record TimesheetView(String start, String end, List<DayHead> days, List<TimesheetRow> rows) {
    }

    public record DayEntryView(String key, String summary, String functionId, String step, double loggedH, double plannedH,
                               List<String> notes) {
    }

    public record DayDetailView(String memberId, String date, double loggedH, double plannedH, List<DayEntryView> entries) {
    }

    private static Context ctx(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            return Context.load(u, d.clock().today());
        }
    }

    private static Capacity.Plan plan(Context ctx) {
        List<PlanItem> items = new ArrayList<>();
        for (Issue i : ctx.subtasks()) {
            if (Py.truthy(i.plannedStart()) && Py.truthy(i.plannedEnd())) {
                String w = ctx.worker(i);
                if (Py.truthy(w)) {
                    items.add(new PlanItem(i.key(), w, i.plannedStart(), i.plannedEnd(), i.estimateH()));
                }
            }
        }
        return Capacity.dailyPlan(items, ctx.cal);
    }

    public static TimesheetView timesheet(Deps d, String start, String end) {
        if (end.compareTo(start) < 0 || Dates.dayDiff(start, end) >= MAX_DAYS) {
            throw new Invalid("Choose a range of 1 to " + MAX_DAYS + " days", "to");
        }
        Context ctx = ctx(d);
        List<String> days = Dates.daysBetween(start, end).stream().filter(ctx.cal::visible).toList();
        Map<String, List<Worklog>> logs = new HashMap<>();
        for (Issue i : ctx.issues) {
            for (Worklog w : i.worklogs()) {
                if (start.compareTo(w.date()) <= 0 && w.date().compareTo(end) <= 0) {
                    logs.computeIfAbsent(Timesheet.logKey(w.memberId(), w.date()), k -> new ArrayList<>()).add(w);
                }
            }
        }
        WorkingHours wh = ctx.settings.workingHours;
        List<TimesheetRow> rows = Timesheet.timesheet(new ArrayList<>(ctx.members.keySet()), days, logs, plan(ctx), ctx.cal,
                ctx.today, wh.hoursPerDay(), wh.timesheetToleranceH());
        return new TimesheetView(start, end,
                days.stream().map(x -> new DayHead(x, x.equals(ctx.today), ctx.cal.extraDays().get(x))).toList(), rows);
    }

    public static DayDetailView dayDetail(Deps d, String memberId, String date) {
        Context ctx = ctx(d);
        if (!ctx.members.containsKey(memberId)) {
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
    public record EffortKpis(double loggedH, double estimateH, Double reworkRate, Double avgRounds, int fixTickets,
                             int overSubtasks) {
    }

    public record EffortAnalysis(String epicKey, String title, EffortKpis kpis, List<EffortBar> byPhase, List<EffortBar> byStep,
                                 List<Rework> rework) {
    }

    public static EffortAnalysis analysis(Deps d, String requested) {
        Context ctx = ctx(d);
        List<String> withFns = ctx.settings.jira.epics.stream()
                .filter(k -> ctx.functions.stream().anyMatch(f -> f.epicKey().equals(k))).toList();
        String epic = Py.truthy(requested) ? requested : (withFns.isEmpty() ? null : withFns.get(0));
        if (Py.truthy(epic) && ctx.anken.stream().noneMatch(a -> a.key().equals(epic))) {
            throw new NotFound("Anken " + epic + " is not tracked", "anken");
        }
        String title = ctx.anken.stream().filter(a -> a.key().equals(epic)).map(tracker.domain.Anken::title).findFirst().orElse("");
        List<Issue> subs = ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic)).toList();
        List<Function> fns = ctx.functions.stream().filter(f -> f.epicKey().equals(epic)).toList();
        List<Issue> cells = fns.stream().flatMap(f -> f.issues().stream()).toList();
        Map<String, String> phaseOfParent = new HashMap<>();
        ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).forEach(p -> phaseOfParent.put(p.key(), p.phase()));
        Map<String, List<Issue>> byPhase = new LinkedHashMap<>();
        for (Issue i : subs) {
            byPhase.computeIfAbsent(phaseOfParent.getOrDefault(i.parentKey() == null ? "" : i.parentKey(), i.phase()),
                    k -> new ArrayList<>()).add(i);
        }
        Map<String, Integer> rounds = new HashMap<>();
        ctx.flags.forEach((k, f) -> rounds.put(k, f.rounds()));
        List<String> bas = ctx.members.entrySet().stream()
                .filter(e -> e.getValue().role() == Role.BA && fns.stream().anyMatch(f -> Objects.equals(f.ownerId(), e.getKey())))
                .map(Map.Entry::getKey).toList();
        List<Rework> rework = Analysis.reworkByMember(fns, bas, rounds);
        List<Issue> started = cells.stream().filter(c -> c.status() != Status.NYS).toList();
        List<Issue> fixes = cells.stream().filter(c -> Steps.isFixStep(c) && rounds.getOrDefault(c.key(), 0) > 0).toList();
        double createH = Num.sum(rework.stream().mapToDouble(Rework::createH));
        double fixH = Num.sum(rework.stream().mapToDouble(Rework::fixH));
        EffortKpis kpis = new EffortKpis(
                Num.round(Num.sum(started.stream().mapToDouble(Issue::loggedH)), 2),
                Num.round(Num.sum(started.stream().mapToDouble(Issue::estimateH)), 2),
                createH != 0 ? Num.round(fixH / createH, 4) : null,
                fixes.isEmpty() ? null : Num.round(fixes.stream().mapToInt(c -> rounds.get(c.key())).sum() / (double) fixes.size(), 2),
                fixes.size(), (int) started.stream().filter(c -> ctx.flags.get(c.key()).over()).count());
        List<Analysis.Group> phaseGroups = Overview.orderedPhases(byPhase.keySet()).stream()
                .map(p -> new Analysis.Group(p, byPhase.get(p))).toList();
        List<Analysis.Group> stepGroups = new ArrayList<>();
        for (StepDef s : Steps.STEPS) {
            stepGroups.add(new Analysis.Group(s.label(), cells.stream().filter(c -> c.step() == s.kind()).toList()));
        }
        return new EffortAnalysis(epic, title, kpis, Analysis.estimateVsLogged(phaseGroups), Analysis.estimateVsLogged(stepGroups),
                rework);
    }
}
