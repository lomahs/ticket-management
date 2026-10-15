package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.SyncService;
import tracker.application.TaskCard;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.dto.NewSubtask;
import tracker.application.ports.JiraWriter;
import tracker.domain.Anken;
import tracker.domain.DateReason;
import tracker.domain.Dates;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Naming;
import tracker.domain.Numbers;
import tracker.domain.Overview.PhaseRollup;
import tracker.domain.Overview;
import tracker.domain.Scheduling;
import tracker.domain.StepKind;
import tracker.domain.Steps;
import tracker.domain.TaskTemplate;
import tracker.domain.Text;
import tracker.domain.WorkStructure;

/** Anken (tracked epics): overview cards, detail with attention groups, the function matrix, and new functions. */
@Service
public class AnkenService {
    private final TrackerContextLoader context;
    private final Optional<JiraWriter> writer;
    private final SyncService sync;

    public AnkenService(TrackerContextLoader context, Optional<JiraWriter> writer, SyncService sync) {
        this.context = context;
        this.writer = writer;
        this.sync = sync;
    }

    /** @param slipDays working days the forecast is past the plan */
    public record AnkenCard(String key, String title, String project, @Nullable String plan, @Nullable String forecast, int slipDays,
                            int lateFunctions, int lateSubtasks, double usedH, double estimateH, List<PhaseRollup> phases,
                            List<String> memberIds, boolean hasFunctions) {
    }

    /** @param items at most 6 */
    public record AttentionView(String kind, int count, List<TaskCard> items) {
    }

    public record AnkenDetail(AnkenCard card, List<AttentionView> attention) {
    }

    private static List<Issue> epicSubtasks(TrackerContext ctx, String epic) {
        return ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic)).toList();
    }

    private static Anken anken(TrackerContext ctx, String key) {
        return ctx.anken(key).orElseThrow(() -> new NotFound("Anken " + key + " is not tracked", "epic"));
    }

    private static AnkenCard card(TrackerContext ctx, String key) {
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
        List<PhaseRollup> phases = ctx.settings.structure.orderedPhases(names).stream()
                .map(n -> Overview.phaseRollup(n, byPhase.getOrDefault(n, List.of()))).toList();
        List<Issue> late = subs.stream().filter(i -> ctx.flags.get(i.key()).late()).toList();
        List<Function> fns = ctx.functions.stream().filter(f -> f.epicKey().equals(key)).toList();
        Set<String> lateKeys = new HashSet<>(late.stream().map(Issue::key).toList());
        String plan = Text.hasText(a.dueDate()) ? a.dueDate()
                : subs.stream().map(Issue::plannedEnd).filter(Text::hasText).max(String::compareTo).orElse(null);
        int worst = late.stream().mapToInt(i -> ctx.flags.get(i.key()).lateDays()).max().orElse(0);
        Set<String> assignees = new HashSet<>();
        subs.stream().map(Issue::assigneeId).filter(Text::hasText).forEach(assignees::add);
        return new AnkenCard(
                a.key(), a.title(), a.project(), plan, Text.hasText(plan) ? Overview.forecastDate(plan, worst) : null,
                Text.hasText(plan) ? worst : 0,
                (int) fns.stream().filter(f -> f.issues().stream().anyMatch(c -> lateKeys.contains(c.key()))).count(),
                late.size(),
                Numbers.round(subs.stream().mapToDouble(Issue::loggedH).sum(), 2),
                Numbers.round(subs.stream().mapToDouble(Issue::estimateH).sum(), 2),
                phases, ctx.members.keySet().stream().filter(assignees::contains).toList(), !fns.isEmpty());
    }

    public List<AnkenCard> overview() {
        TrackerContext ctx = context.load();
        Map<String, Integer> order = new HashMap<>();
        for (int n = 0; n < ctx.settings.jira.epics.size(); n++) {
            order.put(ctx.settings.jira.epics.get(n), n);
        }
        List<Anken> sorted = new ArrayList<>(ctx.anken);
        sorted.sort(Comparator.comparingInt(a -> order.getOrDefault(a.key(), 99)));
        return sorted.stream().map(a -> card(ctx, a.key())).toList();
    }

    private static void require(TrackerContext ctx, String epic) {
        anken(ctx, epic);
    }

    public AnkenDetail detail(String epic) {
        TrackerContext ctx = context.load();
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
    public record FunctionRow(String id, String name, @Nullable String ownerId, @Nullable String reviewerId, int current, String state,
                              String progress, String group, int pct, List<@Nullable TaskCard> cells, double loggedH, double estimateH) {
    }

    public record MatrixStats(int progressPct, int functionsDone, int functionsTotal, int lateFunctions, int lateSubtasks,
                              int overSubtasks, double loggedH, double estimateH) {
    }

    /**
     * @param prefix    the value of {prefix} for this Task's sub-tasks
     * @param workflow  whether functions can be added under this Task
     * @param functions how many functions the Task has
     */
    public record ParentOption(String key, String summary, String phase, String prefix, boolean workflow, int functions) {
    }

    /** @param taskKey the Task whose functions are shown; null when the epic has no Task with functions */
    public record MatrixView(String epicKey, String title, String phase, MatrixStats stats, List<FunctionRow> functions,
                             List<ParentOption> parents, String nextFunctionId, @Nullable String taskKey) {
    }

    private static final Pattern FUNCTION_ID = Pattern.compile("([A-Za-z]+)(\\d+)");

    /** The most common key among {@code items}; ties go to the one seen first. */
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

    /** The epic's Tasks in phase order, each with its template's prefix and workflow. */
    private static List<ParentOption> parentOptions(TrackerContext ctx, String epic) {
        WorkStructure st = ctx.settings.structure;
        List<Issue> parents = ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList();
        List<String> order = st.orderedPhases(parents.stream().map(Issue::phase).toList());
        List<Issue> sorted = new ArrayList<>(parents);
        sorted.sort(Comparator.comparingInt((Issue p) -> order.indexOf(p.phase())).thenComparing(Issue::key));
        return sorted.stream().map(p -> {
            TaskTemplate t = st.taskTemplate(p.summary());
            int functions = (int) ctx.functions.stream().filter(f -> f.parentKey().equals(p.key())).count();
            return new ParentOption(p.key(), p.summary(), p.phase(), t != null ? t.prefix() : p.phase(), t == null || t.workflow(),
                    functions);
        }).toList();
    }

    /** @param task the Task to show; null shows the first Task that has functions */
    public MatrixView matrix(String epic, @Nullable String task) {
        TrackerContext ctx = context.load();
        require(ctx, epic);
        List<ParentOption> parents = parentOptions(ctx, epic);
        if (task != null && parents.stream().noneMatch(p -> p.key().equals(task))) {
            throw new NotFound(task + " is not a Task of " + epic, "task");
        }
        String shown = task != null ? task : parents.stream().filter(p -> p.functions() > 0).map(ParentOption::key).findFirst().orElse(null);
        List<Function> fns = ctx.functions.stream().filter(f -> f.epicKey().equals(epic) && f.parentKey().equals(shown)).toList();
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
                Numbers.round(f.issues().stream().mapToDouble(Issue::loggedH).sum(), 2),
                Numbers.round(f.issues().stream().mapToDouble(Issue::estimateH).sum(), 2))).toList();
        List<Issue> cells = fns.stream().flatMap(f -> f.issues().stream()).toList();
        MatrixStats stats = new MatrixStats(
                rows.isEmpty() ? 0 : (int) Math.round(rows.stream().mapToInt(FunctionRow::pct).sum() / (double) rows.size()),
                (int) fns.stream().filter(f -> f.current() >= Steps.COUNT).count(), fns.size(),
                (int) rows.stream().filter(r -> r.state().equals("delay")).count(),
                (int) cells.stream().filter(c -> late.contains(c.key())).count(),
                (int) cells.stream().filter(c -> ctx.flags.get(c.key()).over()).count(),
                Numbers.round(cells.stream().mapToDouble(Issue::loggedH).sum(), 2),
                Numbers.round(cells.stream().mapToDouble(Issue::estimateH).sum(), 2));
        String phase = parents.stream().filter(p -> p.key().equals(shown)).map(ParentOption::phase).findFirst().orElse("");
        return new MatrixView(epic, anken(ctx, epic).title(), phase, stats, rows, parents, nextFunctionId(fns), shown);
    }

    // --- new function -------------------------------------------------------------------------------------------------

    /** @param overrides per step: {"start": …, "end": …} or null */
    public record FunctionDraft(String parentKey, String name, String ba, String bal, String jm, double createHours,
                                List<@Nullable Map<String, String>> overrides) {
    }

    /** @param note auto | skips | edited | dayoff */
    public record PreviewStep(String step, String summary, String assigneeId, double estimateH, String start, String end,
                              String note, List<DateReason> reasons) {
    }

    /** @param functionId "" when the name rule has no {function_id} */
    public record FunctionPreview(String phase, String functionId, List<PreviewStep> steps, String prefix) {
    }

    /** How a step is written in new sub-task Summaries: its first alias in the name rules. */
    private static String stepText(TrackerContext ctx, StepKind kind) {
        String first = ctx.settings.nameRules.aliases.getOrDefault(kind, "").split(",", -1)[0].strip();
        return first.isEmpty() ? Steps.STEPS.get(Steps.index(kind)).label() : first;
    }

    private static boolean ruleHasId(TrackerContext ctx) {
        return ctx.settings.nameRules.rule.contains("{function_id}");
    }

    private static FunctionPreview plan(TrackerContext ctx, String epic, FunctionDraft draft, @Nullable String functionId) {
        ParentOption parent = parentOptions(ctx, epic).stream().filter(p -> p.key().equals(draft.parentKey())).findFirst().orElse(null);
        if (parent == null) {
            throw new Invalid(draft.parentKey() + " is not a parent ticket of " + epic, "parent");
        }
        if (!parent.workflow()) {
            throw new Invalid(parent.summary() + " has no function workflow", "parent");
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
        List<@Nullable Map<String, String>> overrides = new ArrayList<>(draft.overrides());
        while (overrides.size() < 5) {
            overrides.add(null);
        }
        overrides = overrides.subList(0, 5);
        String fid = Text.hasText(functionId) ? functionId
                : ruleHasId(ctx) ? nextFunctionId(ctx.functions.stream().filter(f -> f.parentKey().equals(parent.key())).toList()) : "";
        List<Scheduling.PlannedStep> plan = Scheduling.suggestFunctionPlan(Dates.addDays(ctx.today, 1), draft.createHours(),
                List.of(draft.ba(), draft.bal(), draft.ba(), draft.jm(), draft.ba()), ctx.cal, overrides);
        String rule = ctx.settings.nameRules.rule;
        String ankenName = anken(ctx, epic).title();
        List<PreviewStep> steps = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Scheduling.PlannedStep p = plan.get(i);
            Map<String, String> values = new HashMap<>();
            values.put("phase_name", parent.phase());
            values.put("prefix", parent.prefix());
            values.put("function_id", fid);
            values.put("function_name", Text.orElse(draft.name(), "(function name)"));
            values.put("step", stepText(ctx, p.step()));
            values.put("anken_name", ankenName);
            steps.add(new PreviewStep(p.step().value(), Naming.renderSummary(rule, values), p.assigneeId(), p.estimateH(),
                    p.start(), p.end(), p.note(), p.reasons()));
        }
        return new FunctionPreview(parent.phase(), fid, steps, parent.prefix());
    }

    public FunctionPreview previewFunction(String epic, FunctionDraft draft) {
        TrackerContext ctx = context.load();
        require(ctx, epic);
        return plan(ctx, epic, draft, null);
    }

    /** Creates the five step sub-tasks in Jira and reads them back. */
    public List<String> createFunction(String epic, FunctionDraft draft, @Nullable String functionId) {
        JiraWriter jira = writer.orElseThrow(() -> new CapabilityDisabled("Writing to Jira is not available yet"));
        TrackerContext ctx = context.load();
        require(ctx, epic);
        if (draft.name().strip().isEmpty()) {
            throw new Invalid("Function name is required", "name");
        }
        List<Function> inTask = ctx.functions.stream()
                .filter(f -> f.epicKey().equals(epic) && f.parentKey().equals(draft.parentKey())).toList();
        if (Text.hasText(functionId) && inTask.stream().anyMatch(f -> f.id().equals(functionId))) {
            throw new Conflict("Function " + functionId + " already exists in " + draft.parentKey(), "function_id");
        }
        String name = draft.name().strip();
        if (!ruleHasId(ctx) && inTask.stream().anyMatch(f -> f.id().equals(name))) {
            throw new Conflict("Function " + name + " already exists in " + draft.parentKey(), "name");
        }
        FunctionPreview p = plan(ctx, epic, draft, functionId);
        List<NewSubtask> subs = new ArrayList<>();
        for (PreviewStep s : p.steps()) {
            String acc = ctx.members.get(s.assigneeId()).jiraAccountId();
            if (!Text.hasText(acc)) {
                throw new Invalid(ctx.members.get(s.assigneeId()).name() + " has no Jira account", "assignee");
            }
            subs.add(new NewSubtask(s.summary(), acc, s.start(), s.end(), s.estimateH()));
        }
        List<String> keys = jira.createSubtasks(draft.parentKey(), subs);
        sync.refresh(keys);
        return keys;
    }
}
