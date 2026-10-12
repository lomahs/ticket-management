package tracker.application.usecases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.Context;
import tracker.application.Deps;
import tracker.application.TaskCard;
import tracker.application.dto.NewSubtask;
import tracker.application.ports.UnitOfWork;
import tracker.domain.Anken;
import tracker.domain.DateReason;
import tracker.domain.Dates;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Naming;
import tracker.domain.Num;
import tracker.domain.Overview;
import tracker.domain.Overview.PhaseRollup;
import tracker.domain.Py;
import tracker.domain.Scheduling;
import tracker.domain.Steps;

/** Anken (tracked epics): overview cards, detail with attention groups, the function matrix, and new functions. */
public final class AnkenUseCases {
    private AnkenUseCases() {
    }

    /** @param slipDays working days the forecast is past the plan */
    public record AnkenCard(String key, String title, String project, String plan, String forecast, int slipDays,
                            int lateFunctions, int lateSubtasks, double usedH, double estimateH, List<PhaseRollup> phases,
                            List<String> memberIds, boolean hasFunctions) {
    }

    /** @param items at most 6 */
    public record AttentionView(String kind, int count, List<TaskCard> items) {
    }

    public record AnkenDetail(AnkenCard card, List<AttentionView> attention) {
    }

    private static Context ctx(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            return Context.load(u, d.clock().today());
        }
    }

    private static List<Issue> epicSubtasks(Context ctx, String epic) {
        return ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic)).toList();
    }

    private static Anken anken(Context ctx, String key) {
        return ctx.anken.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
    }

    private static AnkenCard card(Context ctx, String key) {
        Anken a = anken(ctx, key);
        List<Issue> subs = epicSubtasks(ctx, key);
        List<Issue> parents = ctx.parents().stream().filter(p -> p.epicKey().equals(key)).toList();
        Map<String, List<Issue>> byPhase = new LinkedHashMap<>();
        Map<String, String> phaseOfParent = new HashMap<>();
        parents.forEach(p -> phaseOfParent.put(p.key(), p.phase()));
        for (Issue i : subs) {
            String phase = phaseOfParent.getOrDefault(i.parentKey() == null ? "" : i.parentKey(), i.phase());
            byPhase.computeIfAbsent(phase, x -> new ArrayList<>()).add(i);
        }
        Set<String> names = new LinkedHashSet<>();
        parents.forEach(p -> names.add(p.phase()));
        names.addAll(byPhase.keySet());
        List<PhaseRollup> phases = Overview.orderedPhases(names).stream()
                .map(n -> Overview.phaseRollup(n, byPhase.getOrDefault(n, List.of()))).toList();
        List<Issue> late = subs.stream().filter(i -> ctx.flags.get(i.key()).late()).toList();
        List<Function> fns = ctx.functions.stream().filter(f -> f.epicKey().equals(key)).toList();
        Set<String> lateKeys = new HashSet<>(late.stream().map(Issue::key).toList());
        String plan = Py.truthy(a.dueDate()) ? a.dueDate()
                : subs.stream().map(Issue::plannedEnd).filter(Py::truthy).max(String::compareTo).orElse(null);
        int worst = late.stream().mapToInt(i -> ctx.flags.get(i.key()).lateDays()).max().orElse(0);
        Set<String> assignees = new HashSet<>();
        subs.stream().map(Issue::assigneeId).filter(Py::truthy).forEach(assignees::add);
        return new AnkenCard(
                a.key(), a.title(), a.project(), plan, Py.truthy(plan) ? Overview.forecastDate(plan, worst) : null,
                Py.truthy(plan) ? worst : 0,
                (int) fns.stream().filter(f -> f.issues().stream().anyMatch(c -> lateKeys.contains(c.key()))).count(),
                late.size(),
                Num.round(Num.sum(subs.stream().mapToDouble(Issue::loggedH)), 2),
                Num.round(Num.sum(subs.stream().mapToDouble(Issue::estimateH)), 2),
                phases, ctx.members.keySet().stream().filter(assignees::contains).toList(), !fns.isEmpty());
    }

    public static List<AnkenCard> overview(Deps d) {
        Context ctx = ctx(d);
        Map<String, Integer> order = new HashMap<>();
        for (int n = 0; n < ctx.settings.jira.epics.size(); n++) {
            order.put(ctx.settings.jira.epics.get(n), n);
        }
        List<Anken> sorted = new ArrayList<>(ctx.anken);
        sorted.sort(Comparator.comparingInt(a -> order.getOrDefault(a.key(), 99)));
        return sorted.stream().map(a -> card(ctx, a.key())).toList();
    }

    private static void require(Context ctx, String epic) {
        if (ctx.anken.stream().noneMatch(a -> a.key().equals(epic))) {
            throw new NotFound("Anken " + epic + " is not tracked", "epic");
        }
    }

    public static AnkenDetail detail(Deps d, String epic) {
        Context ctx = ctx(d);
        require(ctx, epic);
        List<Issue> subs = epicSubtasks(ctx, epic);
        List<AttentionView> attention = Overview.attentionGroups(subs, ctx.flags, ctx.members, ctx.settings.thresholds).stream()
                .map(g -> new AttentionView(g.kind(), g.issues().size(),
                        g.issues().subList(0, Math.min(6, g.issues().size())).stream().map(ctx::card).toList()))
                .toList();
        return new AnkenDetail(card(ctx, epic), attention);
    }

    // --- matrix -----------------------------------------------------------------------------------------------------

    /**
     * @param state    done | nys | delay | ontime
     * @param progress early | ontime | late
     * @param group    N | W | D
     */
    public record FunctionRow(String id, String name, String ownerId, String reviewerId, int current, String state,
                              String progress, String group, int pct, List<TaskCard> cells, double loggedH, double estimateH) {
    }

    public record MatrixStats(int progressPct, int functionsDone, int functionsTotal, int lateFunctions, int lateSubtasks,
                              int overSubtasks, double loggedH, double estimateH) {
    }

    public record ParentOption(String key, String summary, String phase) {
    }

    public record MatrixView(String epicKey, String title, String phase, MatrixStats stats, List<FunctionRow> functions,
                             List<ParentOption> parents, String nextFunctionId) {
    }

    private static final Pattern FUNCTION_ID = Pattern.compile("([A-Za-z]+)(\\d+)");

    /** The most common key among {@code items}; ties go to the one seen first (Counter.most_common). */
    static String mostCommon(List<String> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        items.forEach(x -> counts.merge(x, 1, Integer::sum));
        String best = null;
        int max = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > max) {
                best = e.getKey();
                max = e.getValue();
            }
        }
        return best;
    }

    static String nextFunctionId(List<Function> fns) {
        List<String[]> ids = new ArrayList<>();
        for (Function f : fns) {
            Matcher m = FUNCTION_ID.matcher(f.id());
            if (m.matches()) {
                ids.add(new String[]{m.group(1), m.group(2)});
            }
        }
        if (ids.isEmpty()) {
            return "F01";
        }
        String prefix = mostCommon(ids.stream().map(x -> x[0]).toList());
        List<String> nums = ids.stream().filter(x -> x[0].equals(prefix)).map(x -> x[1]).toList();
        int width = nums.stream().mapToInt(String::length).max().orElse(0);
        long next = nums.stream().mapToLong(Long::parseLong).max().orElse(0) + 1;
        return prefix + String.format("%0" + width + "d", next);
    }

    public static MatrixView matrix(Deps d, String epic) {
        Context ctx = ctx(d);
        require(ctx, epic);
        List<Function> fns = ctx.functions.stream().filter(f -> f.epicKey().equals(epic)).toList();
        Set<String> late = new HashSet<>();
        ctx.flags.forEach((k, f) -> {
            if (f.late()) {
                late.add(k);
            }
        });
        List<FunctionRow> rows = fns.stream().map(f -> new FunctionRow(
                f.id(), f.name(), f.ownerId(), f.reviewerId(), f.current(),
                Flags.functionState(f, late), Flags.functionProgress(f, ctx.today, late), Flags.groupStatus(f),
                Flags.progressPct(f),
                f.cells().stream().map(c -> c != null ? ctx.card(c) : null).toList(),
                Num.round(Num.sum(f.issues().stream().mapToDouble(Issue::loggedH)), 2),
                Num.round(Num.sum(f.issues().stream().mapToDouble(Issue::estimateH)), 2))).toList();
        List<Issue> cells = fns.stream().flatMap(f -> f.issues().stream()).toList();
        MatrixStats stats = new MatrixStats(
                rows.isEmpty() ? 0 : Num.round(rows.stream().mapToInt(FunctionRow::pct).sum() / (double) rows.size()),
                (int) fns.stream().filter(f -> f.current() >= Steps.COUNT).count(), fns.size(),
                (int) rows.stream().filter(r -> r.state().equals("delay")).count(),
                (int) cells.stream().filter(c -> late.contains(c.key())).count(),
                (int) cells.stream().filter(c -> ctx.flags.get(c.key()).over()).count(),
                Num.round(Num.sum(cells.stream().mapToDouble(Issue::loggedH)), 2),
                Num.round(Num.sum(cells.stream().mapToDouble(Issue::estimateH)), 2));
        String phase = mostCommon(cells.stream().map(Issue::phase).toList());
        List<Issue> parents = new ArrayList<>(ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList());
        parents.sort(Comparator.comparing(Issue::key));
        String title = anken(ctx, epic).title();
        return new MatrixView(epic, title, phase != null ? phase : "", stats, rows,
                parents.stream().map(p -> new ParentOption(p.key(), p.summary(), p.phase())).toList(), nextFunctionId(fns));
    }

    // --- new function -------------------------------------------------------------------------------------------------

    /** @param overrides per step: {"start": …, "end": …} or null */
    public record FunctionDraft(String parentKey, String name, String ba, String bal, String jm, double createHours,
                                List<Map<String, String>> overrides) {
    }

    /** @param note auto | skips | edited | dayoff */
    public record PreviewStep(String step, String summary, String assigneeId, double estimateH, String start, String end,
                              String note, List<DateReason> reasons) {
    }

    public record FunctionPreview(String phase, String functionId, List<PreviewStep> steps) {
    }

    private static FunctionPreview plan(Context ctx, String epic, FunctionDraft draft, String functionId) {
        Issue parent = ctx.parents().stream()
                .filter(p -> p.key().equals(draft.parentKey()) && p.epicKey().equals(epic)).findFirst().orElse(null);
        if (parent == null) {
            throw new Invalid(draft.parentKey() + " is not a parent ticket of " + epic, "parent");
        }
        String[][] people = {{"ba", draft.ba()}, {"bal", draft.bal()}, {"jm", draft.jm()}};
        for (String[] p : people) {
            if (!ctx.members.containsKey(p[1])) {
                throw new Invalid("Unknown member " + p[1], p[0]);
            }
        }
        if (!(draft.createHours() > 0)) {
            throw new Invalid("Effort for Create must be more than 0 hours", "create_hours");
        }
        List<Map<String, String>> overrides = new ArrayList<>(draft.overrides());
        while (overrides.size() < 5) {
            overrides.add(null);
        }
        overrides = overrides.subList(0, 5);
        String fid = Py.truthy(functionId) ? functionId
                : nextFunctionId(ctx.functions.stream().filter(f -> f.epicKey().equals(epic)).toList());
        List<Scheduling.PlannedStep> plan = Scheduling.suggestFunctionPlan(Dates.addDays(ctx.today, 1), draft.createHours(),
                List.of(draft.ba(), draft.bal(), draft.ba(), draft.jm(), draft.ba()), ctx.cal, overrides);
        String rule = ctx.settings.nameRules.rule;
        String ankenName = anken(ctx, epic).title();
        List<PreviewStep> steps = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Scheduling.PlannedStep p = plan.get(i);
            Map<String, String> values = new HashMap<>();
            values.put("phase_name", parent.phase());
            values.put("function_id", fid);
            values.put("function_name", Py.or(draft.name(), "(function name)"));
            values.put("step", Steps.STEPS.get(i).label());
            values.put("anken_name", ankenName);
            steps.add(new PreviewStep(p.step().value(), Naming.renderSummary(rule, values), p.assigneeId(), p.estimateH(),
                    p.start(), p.end(), p.note(), p.reasons()));
        }
        return new FunctionPreview(parent.phase(), fid, steps);
    }

    public static FunctionPreview previewFunction(Deps d, String epic, FunctionDraft draft) {
        Context ctx = ctx(d);
        require(ctx, epic);
        return plan(ctx, epic, draft, null);
    }

    public static List<String> createFunction(Deps d, String epic, FunctionDraft draft, String functionId) {
        if (d.writer() == null) {
            throw new CapabilityDisabled("Writing to Jira is not available yet");
        }
        Context ctx = ctx(d);
        require(ctx, epic);
        if (draft.name().strip().isEmpty()) {
            throw new Invalid("Function name is required", "name");
        }
        if (Py.truthy(functionId)
                && ctx.functions.stream().anyMatch(f -> f.epicKey().equals(epic) && f.id().equals(functionId))) {
            throw new Conflict("Function " + functionId + " already exists in " + epic, "function_id");
        }
        FunctionPreview p = plan(ctx, epic, draft, functionId);
        List<NewSubtask> subs = new ArrayList<>();
        for (PreviewStep s : p.steps()) {
            String acc = ctx.members.get(s.assigneeId()).jiraAccountId();
            if (!Py.truthy(acc)) {
                throw new Invalid(ctx.members.get(s.assigneeId()).name() + " has no Jira account", "assignee");
            }
            subs.add(new NewSubtask(s.summary(), acc, s.start(), s.end(), s.estimateH()));
        }
        List<String> keys = d.writer().createSubtasks(draft.parentKey(), subs);
        d.sync().refresh(keys);
        return keys;
    }
}
