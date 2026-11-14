package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import tracker.application.TrackerContext;
import tracker.application.ports.PlanCellRepository.Cell;
import tracker.application.ports.PlanCellRepository.JiraEdit;
import tracker.application.service.DetailPlanService.DayHours;
import tracker.application.service.DetailPlanService.MatrixLine;
import tracker.domain.DetailMatrix;
import tracker.domain.DetailScheduler;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.Effort;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.Numbers;
import tracker.domain.PhaseGroups;
import tracker.domain.PlanRows;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.StepWeights;
import tracker.domain.Text;
import tracker.domain.Worklog;

/**
 * The lines of the detail plan's matrix: one per step of the unpushed rows, then one per sub-task of the anken in Jira; with
 * who carries each cell's hours ({@code parts}) and the steps of the unpushed rows as placed ({@code placed}).
 */
final class PlanLines {
    private PlanLines() {
    }

    /**
     * @param parts  each cell's hours by its people's shares; none for a removed step or a Closed sub-task
     * @param placed one per share of each unpushed step not removed that has cells: its hours and first and last day
     */
    record Built(List<MatrixLine> lines, List<DetailScheduler.Part> parts, List<Placed> placed) {
    }

    /**
     * A line before Planned, Not yet, Start, End and the step order are worked out from its cells.
     *
     * @param shares     who carries its cells from today on; {@code pastShares} before today (a pending PIC takes over from today)
     */
    private record Draft(MatrixLine base, DetailMatrix.Line line, List<Effort.PlanShare> shares, List<Effort.PlanShare> pastShares,
                         @Nullable StepKind kind) {
    }

    static Built build(TrackerContext ctx, String epic, List<EstimationRow> rows, Map<Long, List<PlanRows.RowStep>> stepsOf,
                       Map<String, String> names, StepWeights w, List<Cell> rowCells, Map<String, List<Cell>> jiraCells,
                       List<JiraEdit> edits) {
        List<Draft> drafts = new ArrayList<>();
        Map<String, List<Cell>> cellsOf = new HashMap<>();
        rowCells.forEach(c -> cellsOf.computeIfAbsent(c.ref(), k -> new ArrayList<>()).add(c));
        for (EstimationRow r : rows) {
            for (PlanRows.RowStep st : stepsOf.get(r.id())) {
                List<DayHours> cells = sorted(cellsOf.getOrDefault(st.ref(), List.of()).stream()
                        .map(c -> new DayHours(c.day(), c.hours(), c.auto())).toList());
                String step = st.kind() == null ? null : st.kind().value();
                MatrixLine base = new MatrixLine(st.ref(), r.id(), null, r.phase(), r.functionName(), step,
                        names.getOrDefault(st.ref(), r.functionName()), PlanRows.assignee(st, r.baId(), r.balId(), w),
                        st.kind() == StepKind.CREATE ? r.balId() : null, Numbers.hours(st.hours()), 0, 0, 0, null, null, cells, List.of(),
                        false, false, false, st.removed(), st.edit() != null && st.edit().effortH() != null);
                DetailMatrix.Line line = new DetailMatrix.Line(st.ref(), "row:" + r.id(), st.kind() == null ? 0 : st.kind().ordinal(), false,
                        st.hours(), 0, map(cells));
                List<Effort.PlanShare> shares = st.removed() ? List.of() : PlanRows.shares(st, r.baId(), r.balId(), w);
                drafts.add(new Draft(base, line, shares, shares, st.kind()));
            }
        }
        drafts.addAll(jira(ctx, epic, jiraCells, edits));

        // removed and Closed lines count for nothing; a Jira line's days before today are locked, so only its days from today on
        Set<String> outOfOrder = DetailMatrix.outOfOrder(drafts.stream().filter(d -> !d.base().removed() && !d.base().closed())
                .map(d -> d.base().issueKey() == null ? d.line() : fromToday(d.line(), ctx.today)).toList());
        List<MatrixLine> lines = new ArrayList<>();
        List<DetailScheduler.Part> parts = new ArrayList<>();
        List<Placed> placed = new ArrayList<>();
        for (Draft d : drafts) {
            MatrixLine b = d.base();
            DetailMatrix.Line l = d.line();
            String start = DetailMatrix.start(l);
            String end = DetailMatrix.end(l);
            // a Closed sub-task is done: nothing is left to plan, whatever its plan in Jira says
            lines.add(new MatrixLine(b.ref(), b.rowId(), b.issueKey(), b.phase(), b.function(), b.step(), b.summary(), b.picId(), b.reporterId(),
                    b.totalH(), b.spentH(), b.closed() ? b.totalH() : DetailMatrix.plannedH(l, ctx.today),
                    b.closed() ? 0 : DetailMatrix.notYetH(l, ctx.today), start, end, b.cells(),
                    b.logged(), b.closed(), b.pending(), outOfOrder.contains(b.ref()), b.removed(), b.edited()));
            for (DayHours c : b.cells()) {
                for (Effort.PlanShare sh : c.day().compareTo(ctx.today) < 0 ? d.pastShares() : d.shares()) {
                    parts.add(new DetailScheduler.Part(b.ref(), sh.memberId(), c.day(), c.hours() * sh.fraction()));
                }
            }
            for (Effort.PlanShare sh : d.shares()) {
                if (b.issueKey() == null && start != null && end != null) {
                    double sum = b.cells().stream().mapToDouble(DayHours::hours).sum();
                    placed.add(new Placed(b.ref(), b.phase(), d.kind(), sh.memberId(), sum * sh.fraction(), start, end));
                }
            }
        }
        return new Built(lines, parts, placed);
    }

    /**
     * Every sub-task of the anken in Jira, grouped by phase then function. Days before today are Jira's plan (Detail Planning's
     * days as pushed when they all lie within its Planned Start and End in Jira, else {@link TrackerContext#planDays}); from today
     * on the pending cells when the sub-task has any, else the same plan (a pending Remaining or PIC alone keeps it).
     */
    private static List<Draft> jira(TrackerContext ctx, String epic, Map<String, List<Cell>> jiraCells, List<JiraEdit> edits) {
        Map<String, String> groups = DetailPlanService.groupOfTask(ctx, epic);
        Map<String, JiraEdit> editOf = new HashMap<>();
        edits.forEach(e -> editOf.put(e.issueKey(), e));
        // phase → function → its sub-tasks, in the order they first come
        Map<String, Map<String, List<Issue>>> grouped = new LinkedHashMap<>();
        for (Issue i : ctx.subtasks()) {
            if (i.epicKey().equals(epic)) {
                String phase = groups.getOrDefault(Text.orElse(i.parentKey(), ""), PhaseGroups.OTHER);
                grouped.computeIfAbsent(phase, k -> new LinkedHashMap<>()).computeIfAbsent(chain(i), k -> new ArrayList<>()).add(i);
            }
        }
        List<Draft> out = new ArrayList<>();
        grouped.forEach((phase, functions) -> functions.forEach((chain, issues) -> {
            List<Issue> ordered = new ArrayList<>(issues);
            ordered.sort(Comparator.comparingInt(PlanLines::order));
            for (Issue i : ordered) {
                out.add(jiraLine(ctx, phase, chain, i, jiraCells.get(i.key()), editOf.get(i.key())));
            }
        }));
        return out;
    }

    private static Draft jiraLine(TrackerContext ctx, String phase, String chain, Issue i, @Nullable List<Cell> pendingCells,
                                  @Nullable JiraEdit edit) {
        boolean hasCells = pendingCells != null && !pendingCells.isEmpty();
        boolean pending = hasCells || edit != null;
        Map<String, Double> saved = ctx.savedPlanDays(i);
        Map<String, Double> plan = saved != null && within(saved, i) ? saved : ctx.planDays(i);
        List<DayHours> cells = new ArrayList<>();
        plan.forEach((d, h) -> {
            if (d.compareTo(ctx.today) < 0 || !hasCells) {
                cells.add(new DayHours(d, h, false));
            }
        });
        if (hasCells) {
            pendingCells.stream().filter(c -> c.hours() > 0 && c.day().compareTo(ctx.today) >= 0).forEach(c -> cells.add(new DayHours(c.day(), c.hours(), c.auto())));
        }
        List<DayHours> days = sorted(cells);
        boolean closed = i.status() == Status.CLOSED;
        double spent = i.loggedH();
        double remaining = edit != null && edit.remainingH() != null ? edit.remainingH() : i.remainingH();
        double total = Numbers.hours(closed ? spent : spent + remaining);
        String pic = edit != null && edit.assigneeId() != null ? edit.assigneeId() : i.assigneeId();
        Map<String, Double> logged = new TreeMap<>();
        for (Worklog wl : i.worklogs()) {
            logged.merge(wl.date(), wl.hours(), Numbers::sum);
        }
        MatrixLine base = new MatrixLine(i.key(), null, i.key(), phase, Text.hasText(i.functionName()) ? i.functionName() : i.summary(),
                i.step() == null ? null : i.step().value(), i.summary(), pic, i.reporterId(), total, spent, 0, 0, null, null, days,
                logged.entrySet().stream().map(e -> new DayHours(e.getKey(), Numbers.hours(e.getValue()), false)).toList(), closed, pending,
                false, false, false);
        DetailMatrix.Line line = new DetailMatrix.Line(i.key(), chain, order(i), true, total, spent, map(days),
                logged.getOrDefault(ctx.today, 0.0));
        List<Effort.PlanShare> past = closed ? List.of() : ctx.planShares(i);
        return new Draft(base, line, closed ? List.of() : shares(past, i.assigneeId(), pic), past, i.step());
    }

    /** Whether every saved day is within the sub-task's Planned Start and End in Jira (none are without both). */
    private static boolean within(Map<String, Double> days, Issue i) {
        String from = i.plannedStart();
        String to = i.plannedEnd();
        return Text.hasText(from) && Text.hasText(to) && days.keySet().stream().allMatch(d -> d.compareTo(from) >= 0 && d.compareTo(to) <= 0);
    }

    /** The shares of a sub-task in Jira, the one of its assignee moved to the PIC not pushed yet. */
    static List<Effort.PlanShare> shares(List<Effort.PlanShare> shares, @Nullable String assignee, @Nullable String pic) {
        if (assignee == null || pic == null || assignee.equals(pic)) {
            return shares;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        shares.forEach(sh -> out.merge(sh.memberId().equals(assignee) ? pic : sh.memberId(), sh.fraction(), Double::sum));
        return out.entrySet().stream().map(e -> new Effort.PlanShare(e.getKey(), e.getValue())).toList();
    }

    /** {@code l} with its cells from {@code today} on only. */
    private static DetailMatrix.Line fromToday(DetailMatrix.Line l, String today) {
        Map<String, Double> cells = new TreeMap<>(l.cells());
        cells.keySet().removeIf(d -> d.compareTo(today) < 0);
        return new DetailMatrix.Line(l.ref(), l.chain(), l.order(), l.jira(), l.totalH(), l.spentH(), cells, l.loggedTodayH());
    }

    /** The function a sub-task is a step of, else the sub-task alone. */
    static String chain(Issue i) {
        return Text.hasText(i.functionId()) ? "fn:" + i.functionId() : i.key();
    }

    private static int order(Issue i) {
        return i.step() == null ? 0 : i.step().ordinal();
    }

    private static List<DayHours> sorted(List<DayHours> cells) {
        List<DayHours> out = new ArrayList<>(cells);
        out.sort(Comparator.comparing(DayHours::day));
        return List.copyOf(out);
    }

    private static Map<String, Double> map(List<DayHours> cells) {
        Map<String, Double> out = new TreeMap<>();
        cells.forEach(c -> out.merge(c.day(), c.hours(), Numbers::sum));
        return out;
    }
}
