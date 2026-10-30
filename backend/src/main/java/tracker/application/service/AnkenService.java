package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.JiraCalls;
import tracker.application.JiraRequests;
import tracker.application.SyncService;
import tracker.application.TaskCard;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.dto.JiraIssueType;
import tracker.application.dto.JiraRequest;
import tracker.application.dto.JiraResponse;
import tracker.application.ports.JiraError;
import tracker.application.ports.JiraReader;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.DateReason;
import tracker.domain.Dates;
import tracker.domain.EffortState;
import tracker.domain.FinishState;
import tracker.domain.Flags;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.IssueSchedule;
import tracker.domain.Naming;
import tracker.domain.Numbers;
import tracker.domain.Role;
import tracker.domain.Overview.PhaseRollup;
import tracker.domain.Overview.ScheduleRollup;
import tracker.domain.Overview;
import tracker.domain.PhaseGroups;
import tracker.domain.Scheduling;
import tracker.domain.StartState;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Steps;
import tracker.domain.TaskTemplate;
import tracker.domain.Text;
import tracker.domain.Viewer;
import tracker.domain.WorkStructure;

/** Anken (tracked epics): overview cards, detail with attention groups, the function matrix, and new functions. */
@Service
public class AnkenService {
    private final TrackerContextLoader context;
    private final JiraCalls calls;
    private final JiraReader reader;
    private final SyncService sync;

    public AnkenService(TrackerContextLoader context, JiraCalls calls, JiraReader reader, SyncService sync) {
        this.context = context;
        this.calls = calls;
        this.reader = reader;
        this.sync = sync;
    }

    /** @param slipDays working days the forecast is past the plan */
    public record AnkenCard(String key, String title, String project, @Nullable String plan, @Nullable String forecast, int slipDays,
                            int lateFunctions, int lateSubtasks, double usedH, double estimateH, List<PhaseRollup> phases,
                            List<String> memberIds, boolean hasFunctions, List<String> pimIds, ScheduleRollup schedule) {
    }

    /** Someone with work on the epic's sub-tasks who is not a member of the anken: open sub-tasks assigned and hours logged. */
    public record NotMember(String memberId, int openSubtasks, double loggedH) {
    }

    /** @param items at most 6 */
    public record AttentionView(String kind, int count, List<TaskCard> items) {
    }

    /**
     * A Task of the epic with the progress of its sub-tasks, listed under its phase group.
     *
     * @param name the summary without its leading "[...]" groups
     * @param pct  spent / (spent + remaining) over the Task's sub-tasks, like {@link PhaseRollup#pct()}
     */
    public record PhaseTask(String key, String summary, String name, String phase, String status, int pct, int closed, int total,
                            @Nullable String start, @Nullable String end) {
    }

    /**
     * @param tasks      the epic's Tasks, by planned start then key
     * @param notMembers people with work here who are not members; empty unless the viewer may read members
     */
    public record AnkenDetail(AnkenCard card, List<AttentionView> attention, List<PhaseTask> tasks, List<NotMember> notMembers) {
    }

    private static List<Issue> epicSubtasks(TrackerContext ctx, String epic) {
        return ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic)).toList();
    }

    private static Anken anken(TrackerContext ctx, String key) {
        return ctx.anken(key).orElseThrow(() -> new NotFound("Anken " + key + " is not tracked", "epic"));
    }

    /** Task key → phase group of the epic's Tasks. */
    private static Map<String, String> groupOfTask(TrackerContext ctx, String epic) {
        return PhaseGroups.of(ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList(), ctx.settings.structureOf(epic));
    }

    private static AnkenCard card(TrackerContext ctx, String key) {
        Anken a = anken(ctx, key);
        List<Issue> subs = epicSubtasks(ctx, key);
        Map<String, String> groups = groupOfTask(ctx, key);
        Map<String, List<Issue>> byPhase = new LinkedHashMap<>();
        groups.values().forEach(g -> byPhase.computeIfAbsent(g, x -> new ArrayList<>()));
        for (Issue i : subs) {
            String g = groups.get(i.parentKey() == null ? "" : i.parentKey());
            byPhase.computeIfAbsent(g != null ? g : PhaseGroups.OTHER, x -> new ArrayList<>()).add(i);
        }
        double t = ctx.settings.thresholds.effortDiffH();
        List<PhaseRollup> phases = PhaseGroups.ordered(byPhase, ctx.settings.structureOf(key).phases()).stream()
                .map(n -> Overview.phaseRollup(n, byPhase.get(n), ctx.schedule, t)).toList();
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
                phases, ctx.members.keySet().stream().filter(assignees::contains).toList(), !fns.isEmpty(),
                ctx.ankenMembers.getOrDefault(key, Set.of()).stream().sorted()
                        .filter(id -> ctx.members.containsKey(id) && ctx.members.get(id).role() == Role.PIM).toList(),
                Overview.scheduleRollup(subs, ctx.schedule, t));
    }

    /** Assignees of open sub-tasks and authors of worklogs on the epic who are not in its anken, most open sub-tasks first. */
    static List<NotMember> notMembers(TrackerContext ctx, String epic) {
        Map<String, Integer> open = new HashMap<>();
        Map<String, Double> logged = new HashMap<>();
        for (Issue i : epicSubtasks(ctx, epic)) {
            if (i.status() != Status.CLOSED && Text.hasText(i.assigneeId())) {
                open.merge(i.assigneeId(), 1, Integer::sum);
            }
            i.worklogs().forEach(w -> logged.merge(w.memberId(), w.hours(), Double::sum));
        }
        Set<String> ids = new HashSet<>(open.keySet());
        ids.addAll(logged.keySet());
        return ids.stream().filter(id -> ctx.members.containsKey(id) && !ctx.isMember(epic, id))
                .map(id -> new NotMember(id, open.getOrDefault(id, 0), Numbers.round(logged.getOrDefault(id, 0.0), 2)))
                .sorted(Comparator.comparingInt(NotMember::openSubtasks).reversed().thenComparing(NotMember::memberId)).toList();
    }

    /** The not-a-member warning of one anken. */
    public List<NotMember> notMembers(AnkenScope scope, String epic) {
        TrackerContext ctx = context.load(scope);
        require(ctx, epic);
        return notMembers(ctx, epic);
    }

    /** The cards of the anken in scope, in tracked order. */
    public List<AnkenCard> overview(AnkenScope scope) {
        TrackerContext ctx = context.load(scope);
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

    public AnkenDetail detail(AnkenScope scope, String epic) {
        return detail(scope, epic, false);
    }

    /** @param withNotMembers whether to fill {@link AnkenDetail#notMembers} */
    public AnkenDetail detail(AnkenScope scope, String epic, boolean withNotMembers) {
        TrackerContext ctx = context.load(scope);
        require(ctx, epic);
        List<Issue> subs = epicSubtasks(ctx, epic);
        List<AttentionView> attention = Overview.attentionGroups(subs, ctx.flags, ctx.members, ctx.settings.thresholds).stream()
                .map(g -> new AttentionView(g.kind(), g.issues().size(),
                        g.issues().subList(0, Math.min(6, g.issues().size())).stream().map(ctx::card).toList()))
                .toList();
        return new AnkenDetail(card(ctx, epic), attention, phaseTasks(ctx, epic, subs),
                withNotMembers ? notMembers(ctx, epic) : List.of());
    }

    /**
     * The sub-tasks of the epic with the schedule label {@code label}, restricted to the phase group {@code phase} when given,
     * most days (or largest effort difference) first, then by key.
     */
    public List<TaskCard> tickets(AnkenScope scope, String epic, String label, @Nullable String phase) {
        TrackerContext ctx = context.load(scope);
        require(ctx, epic);
        List<Issue> subs = epicSubtasks(ctx, epic);
        if (phase != null) {
            Map<String, String> groups = groupOfTask(ctx, epic);
            if (card(ctx, epic).phases().stream().noneMatch(p -> p.name().equals(phase))) {
                throw new NotFound(phase + " is not a phase of " + epic, "phase");
            }
            subs = subs.stream().filter(i -> phase.equals(groups.getOrDefault(i.parentKey() == null ? "" : i.parentKey(), PhaseGroups.OTHER)))
                    .toList();
        }
        java.util.function.ToDoubleFunction<IssueSchedule> weight = switch (label) {
            case "overdue", "late_finish", "early_finish" -> IssueSchedule::finishDays;
            case "late_start", "early_start" -> IssueSchedule::startDays;
            default -> s -> Math.abs(s.diffH());
        };
        return subs.stream().filter(i -> hasLabel(ctx.schedule.get(i.key()), label))
                .sorted(Comparator.comparingDouble((Issue i) -> -weight.applyAsDouble(ctx.schedule.get(i.key()))).thenComparing(Issue::key))
                .map(ctx::card).toList();
    }

    private static boolean hasLabel(IssueSchedule s, String label) {
        return switch (label) {
            case "overdue" -> s.finish() == FinishState.OVERDUE;
            case "late_start" -> s.start() == StartState.LATE_START;
            case "early_start" -> s.start() == StartState.EARLY_START;
            case "late_finish" -> s.finish() == FinishState.LATE_FINISH;
            case "early_finish" -> s.finish() == FinishState.EARLY_FINISH;
            case "under_burn" -> s.effort() == EffortState.UNDER_BURN;
            case "over_burn" -> s.effort() == EffortState.OVER_BURN;
            default -> throw new IllegalArgumentException(label);
        };
    }

    private static List<PhaseTask> phaseTasks(TrackerContext ctx, String epic, List<Issue> subs) {
        Map<String, String> groups = groupOfTask(ctx, epic);
        return ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).map(t -> {
            PhaseRollup r = Overview.phaseRollup(t.key(), subs.stream().filter(i -> t.key().equals(i.parentKey())).toList(), ctx.schedule,
                    ctx.settings.thresholds.effortDiffH());
            return new PhaseTask(t.key(), t.summary(), PhaseGroups.taskName(t.summary()), groups.get(t.key()), t.status().value(), r.pct(), r.closed(), r.total(),
                    r.start(), r.end());
        }).sorted(Comparator.comparing((PhaseTask t) -> t.start() == null ? "9999" : t.start()).thenComparing(PhaseTask::key))
                .toList();
    }

    // --- matrix -----------------------------------------------------------------------------------------------------

    /**
     * @param state      done | nys | delay | ontime
     * @param progress   early | ontime | late
     * @param group      N | W | D
     * @param baId       the function's BA ({@link Function#baId})
     * @param balId      the function's BAL ({@link Function#balId})
     * @param cells      one slot per step of the function: 3 or 5
     * @param loggedH    on the team steps (JM Review left out); also {@code estimateH}
     * @param taskKey    the Task (sub-phase) the function belongs to
     */
    public record FunctionRow(String id, String name, @Nullable String baId, @Nullable String balId, int current, String state,
                              String progress, String group, int pct, List<@Nullable TaskCard> cells, double loggedH, double estimateH,
                              String taskKey) {
    }

    public record MatrixStats(int progressPct, int functionsDone, int functionsTotal, int lateFunctions, int lateSubtasks,
                              int overSubtasks, double loggedH, double estimateH) {
    }

    /**
     * A Task of the epic: one sub-phase of a phase group.
     *
     * @param name      the Task title without its leading brackets, e.g. "ITA Prepare"
     * @param group     the phase group the Task belongs to
     * @param prefix    the value of {prefix} for this Task's sub-tasks
     * @param workflow  whether functions can be added under this Task
     * @param functions how many functions the Task has
     * @param start     the earliest Planned Start of the Task's sub-tasks
     * @param end       the latest Planned End of the Task's sub-tasks
     */
    public record ParentOption(String key, String summary, String name, String phase, String group, String prefix, boolean workflow,
                               int functions, @Nullable String start, @Nullable String end) {
    }

    /**
     * @param phase     the phase groups of the shown Tasks, joined
     * @param functions the functions of the shown Tasks, Task by Task
     * @param parents   every Task of the epic, by phase group, then planned start
     * @param taskKeys  the shown Tasks, in the order of {@code parents}; empty when the epic has no Tasks
     * @param memberIds the anken's members, by id
     * @param tickets   the single tickets of the shown Tasks (sub-tasks outside the 5-step workflow), Task by Task
     */
    public record MatrixView(String epicKey, String title, String phase, MatrixStats stats, List<FunctionRow> functions,
                             List<ParentOption> parents, String nextFunctionId, List<String> taskKeys,
                             List<String> memberIds, List<MatrixTicket> tickets) {
    }

    /** A single ticket on the matrix: one row of its own, identified by its key (several may share a name). */
    public record MatrixTicket(String taskKey, String name, TaskCard card) {
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

    /** The epic's Tasks by phase group, then the earliest planned start of their sub-tasks, each with its template's prefix and workflow. */
    private static List<ParentOption> parentOptions(TrackerContext ctx, String epic) {
        WorkStructure st = ctx.settings.structureOf(epic);
        List<Issue> parents = ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList();
        Map<String, String> groups = groupOfTask(ctx, epic);
        List<Issue> subs = epicSubtasks(ctx, epic);
        Map<String, List<Issue>> byGroup = new LinkedHashMap<>();
        groups.values().forEach(g -> byGroup.computeIfAbsent(g, x -> new ArrayList<>()));
        subs.forEach(i -> {
            String g = groups.get(Text.orElse(i.parentKey(), ""));
            if (g != null) {
                byGroup.get(g).add(i);
            }
        });
        List<String> order = PhaseGroups.ordered(byGroup, st.phases());
        Map<String, String> start = new HashMap<>();
        Map<String, String> end = new HashMap<>();
        subs.forEach(i -> {
            if (i.parentKey() != null && Text.hasText(i.plannedStart())) {
                start.merge(i.parentKey(), i.plannedStart(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
            if (i.parentKey() != null && Text.hasText(i.plannedEnd())) {
                end.merge(i.parentKey(), i.plannedEnd(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        });
        List<Issue> sorted = new ArrayList<>(parents);
        sorted.sort(Comparator.comparingInt((Issue p) -> order.indexOf(groups.get(p.key())))
                .thenComparing(p -> start.getOrDefault(p.key(), "9999")).thenComparing(Issue::key));
        return sorted.stream().map(p -> {
            TaskTemplate t = st.taskTemplate(p.summary());
            int functions = (int) ctx.functions.stream().filter(f -> f.parentKey().equals(p.key())).count();
            return new ParentOption(p.key(), p.summary(), PhaseGroups.taskName(p.summary()), p.phase(), groups.get(p.key()),
                    t != null ? t.prefix() : p.phase(), t == null || t.workflow(), functions, start.get(p.key()), end.get(p.key()));
        }).toList();
    }

    /**
     * @param tasks the Tasks to show; when empty, every Task of {@code phase}, else the {@code shown} ones, else every Task of the
     *              first phase group that has functions
     * @param phase used when no Tasks are given
     * @param shown the Tasks the viewer's own layout shows; keys that are no longer Tasks of the epic are skipped
     */
    public MatrixView matrix(AnkenScope scope, String epic, @Nullable List<String> tasks, @Nullable String phase,
                             @Nullable List<String> shown) {
        TrackerContext ctx = context.load(scope);
        require(ctx, epic);
        List<ParentOption> parents = parentOptions(ctx, epic);
        List<ParentOption> picked;
        List<ParentOption> saved = shown == null ? List.of() : parents.stream().filter(p -> shown.contains(p.key())).toList();
        if (tasks != null && !tasks.isEmpty()) {
            for (String t : tasks) {
                if (parents.stream().noneMatch(p -> p.key().equals(t))) {
                    throw new NotFound(t + " is not a Task of " + epic, "tasks");
                }
            }
            picked = parents.stream().filter(p -> tasks.contains(p.key())).toList();
        } else if (Text.hasText(phase)) {
            picked = parents.stream().filter(p -> p.group().equals(phase)).toList();
            if (picked.isEmpty()) {
                throw new NotFound(phase + " is not a phase of " + epic, "phase");
            }
        } else if (!saved.isEmpty()) {
            picked = saved;
        } else {
            String first = parents.stream().filter(p -> p.functions() > 0).map(ParentOption::group).findFirst()
                    .orElse(parents.isEmpty() ? null : parents.get(0).group());
            picked = parents.stream().filter(p -> p.group().equals(first)).toList();
        }
        List<String> taskKeys = picked.stream().map(ParentOption::key).toList();
        List<Function> fns = new ArrayList<>();
        for (String t : taskKeys) {
            ctx.functions.stream().filter(f -> f.epicKey().equals(epic) && f.parentKey().equals(t)).forEach(fns::add);
        }
        Set<String> late = new HashSet<>();
        ctx.flags.forEach((k, f) -> {
            if (f.late()) {
                late.add(k);
            }
        });
        List<FunctionRow> rows = fns.stream().map(f -> new FunctionRow(
                f.id(), f.name(), f.baId(ctx.members), f.balId(ctx.members), f.current(),
                Flags.functionState(f, late), Flags.functionProgress(f, ctx.today, late), Flags.groupStatus(f),
                Flags.progressPct(f),
                f.steps().stream().map(c -> c != null ? ctx.card(c) : null).toList(),
                Numbers.round(f.issues().stream().filter(c -> !Steps.isJmReview(c)).mapToDouble(Issue::loggedH).sum(), 2),
                Numbers.round(f.issues().stream().filter(c -> !Steps.isJmReview(c)).mapToDouble(Issue::estimateH).sum(), 2),
                f.parentKey())).toList();
        List<Issue> cells = fns.stream().flatMap(f -> f.issues().stream()).toList();
        List<Issue> team = cells.stream().filter(c -> !Steps.isJmReview(c)).toList();
        MatrixStats stats = new MatrixStats(
                rows.isEmpty() ? 0 : (int) Math.round(rows.stream().mapToInt(FunctionRow::pct).sum() / (double) rows.size()),
                (int) fns.stream().filter(Function::done).count(), fns.size(),
                (int) rows.stream().filter(r -> r.state().equals("delay")).count(),
                (int) cells.stream().filter(c -> late.contains(c.key())).count(),
                (int) cells.stream().filter(c -> ctx.flags.get(c.key()).over()).count(),
                Numbers.round(team.stream().mapToDouble(Issue::loggedH).sum(), 2),
                Numbers.round(team.stream().mapToDouble(Issue::estimateH).sum(), 2));
        List<MatrixTicket> tickets = new ArrayList<>();
        for (String t : taskKeys) {
            ctx.subtasks().stream().filter(i -> t.equals(i.parentKey()) && i.single())
                    .sorted(Comparator.comparing((Issue i) -> Text.orElse(i.plannedStart(), "9999")).thenComparing(Issue::key))
                    .forEach(i -> tickets.add(new MatrixTicket(t, i.functionName(), ctx.card(i))));
        }
        String shownPhase = String.join(", ", picked.stream().map(ParentOption::group).distinct().toList());
        return new MatrixView(epic, anken(ctx, epic).title(), shownPhase, stats, rows, parents, nextFunctionId(fns), taskKeys,
                ctx.ankenMembers.getOrDefault(epic, Set.of()).stream().sorted().toList(), tickets);
    }

    // --- one function -------------------------------------------------------------------------------------------------

    /**
     * @param taskKey the Task the function belongs to
     * @param phase   the phase group of that Task
     * @param steps   one slot per step of the function (3 or 5), in step order; null when that sub-task is not in Jira
     */
    public record FunctionSteps(String epicKey, String taskKey, String phase, String functionId, String name,
                                List<@Nullable TaskCard> steps) {
    }

    /** The function (epic, Task, function id) that the sub-task {@code key} is a step of; a ticket outside the scope is not found. */
    public FunctionSteps functionOf(AnkenScope scope, String key) {
        TrackerContext ctx = context.load(scope);
        ctx.issue(key).orElseThrow(() -> new NotFound("Ticket " + key + " was not found", "key"));
        Function f = ctx.functions.stream().filter(fn -> fn.issues().stream().anyMatch(i -> i.key().equals(key))).findFirst()
                .orElseThrow(() -> new NotFound(key + " is not a step of a function", "key"));
        String phase = groupOfTask(ctx, f.epicKey()).getOrDefault(f.parentKey(), PhaseGroups.OTHER);
        return new FunctionSteps(f.epicKey(), f.parentKey(), phase, f.id(), f.name(),
                f.steps().stream().map(c -> c != null ? ctx.card(c) : null).toList());
    }

    // --- new function -------------------------------------------------------------------------------------------------

    /**
     * @param jm        the JM Review's assignee; only for 5 steps
     * @param overrides per step: {"start": …, "end": …} or null
     * @param steps     3 (Create → BAL Review → Fix comment BAL) or 5
     */
    public record FunctionDraft(String parentKey, String name, String ba, String bal, @Nullable String jm, double createHours,
                                List<@Nullable Map<String, String>> overrides, int steps) {

        public FunctionDraft(String parentKey, String name, String ba, String bal, @Nullable String jm, double createHours,
                             List<@Nullable Map<String, String>> overrides) {
            this(parentKey, name, ba, bal, jm, createHours, overrides, Steps.COUNT);
        }
    }

    /** @param note auto | skips | edited | dayoff */
    public record PreviewStep(String step, String summary, String assigneeId, double estimateH, String start, String end,
                              String note, List<DateReason> reasons) {
    }

    /** @param functionId "" when the name rule has no {function_id} */
    public record FunctionPreview(String phase, String functionId, List<PreviewStep> steps, String prefix) {
    }

    /** How a step is written in new sub-task Summaries: its first alias in the name rules. */
    private static String stepText(TrackerContext ctx, String epic, StepKind kind) {
        String first = ctx.settings.nameRulesOf(epic).aliases.getOrDefault(kind, "").split(",", -1)[0].strip();
        return first.isEmpty() ? Steps.STEPS.get(Steps.index(kind)).label() : first;
    }

    private static boolean ruleHasId(TrackerContext ctx, String epic) {
        return ctx.settings.nameRulesOf(epic).rule.contains("{function_id}");
    }

    private static FunctionPreview plan(TrackerContext ctx, String epic, FunctionDraft draft, @Nullable String functionId) {
        ParentOption parent = parentOptions(ctx, epic).stream().filter(p -> p.key().equals(draft.parentKey())).findFirst().orElse(null);
        if (parent == null) {
            throw new Invalid(draft.parentKey() + " is not a parent ticket of " + epic, "parent");
        }
        if (!parent.workflow()) {
            throw new Invalid(parent.summary() + " has no function workflow", "parent");
        }
        if (draft.steps() != 3 && draft.steps() != Steps.COUNT) {
            throw new Invalid("A function has 3 or 5 steps", "steps");
        }
        boolean five = draft.steps() == Steps.COUNT;
        List<String[]> people = new ArrayList<>(List.of(new String[]{"ba", draft.ba()}, new String[]{"bal", draft.bal()}));
        if (five) {
            people.add(new String[]{"jm", draft.jm()});
        }
        for (String[] p : people) {
            if (!ctx.members.containsKey(p[1])) {
                throw new Invalid("Unknown member " + p[1], p[0]);
            }
            if (!ctx.isMember(epic, p[1])) {
                throw new Invalid(ctx.notAMember(epic, p[1]), p[0]);
            }
        }
        if (!(draft.createHours() > 0)) {
            throw new Invalid("Effort for Create must be more than 0 hours", "create_hours");
        }
        List<@Nullable Map<String, String>> overrides = new ArrayList<>(draft.overrides());
        while (overrides.size() < draft.steps()) {
            overrides.add(null);
        }
        overrides = overrides.subList(0, draft.steps());
        String fid = Text.hasText(functionId) ? functionId
                : ruleHasId(ctx, epic) ? nextFunctionId(ctx.functions.stream().filter(f -> f.parentKey().equals(parent.key())).toList()) : "";
        List<Scheduling.PlannedStep> plan = Scheduling.suggestFunctionPlan(Dates.addDays(ctx.today, 1), draft.createHours(),
                five ? List.of(draft.ba(), draft.bal(), draft.ba(), draft.jm(), draft.ba()) : List.of(draft.ba(), draft.bal(), draft.ba()),
                ctx.cal, overrides);
        String rule = ctx.settings.nameRulesOf(epic).rule;
        String ankenName = anken(ctx, epic).title();
        List<PreviewStep> steps = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Scheduling.PlannedStep p = plan.get(i);
            Map<String, String> values = new HashMap<>();
            values.put("phase_name", parent.phase());
            values.put("prefix", parent.prefix());
            values.put("function_id", fid);
            values.put("function_name", Text.orElse(draft.name(), "(function name)"));
            values.put("step", stepText(ctx, epic, p.step()));
            values.put("anken_name", ankenName);
            steps.add(new PreviewStep(p.step().value(), Naming.renderSummary(rule, values), p.assigneeId(), p.estimateH(),
                    p.start(), p.end(), p.note(), p.reasons()));
        }
        return new FunctionPreview(parent.phase(), fid, steps, parent.prefix());
    }

    /** {@code epic} must be in the scope; BA, BAL and (for 5 steps) JM must be members of its anken. */
    public FunctionPreview previewFunction(AnkenScope scope, String epic, FunctionDraft draft) {
        TrackerContext ctx = context.load(scope);
        require(ctx, epic);
        return plan(ctx, epic, draft, null);
    }

    /**
     * Creates the step sub-tasks (3 or 5) in Jira, one logged request each, and reads them back; the rules of
     * {@link #previewFunction} apply.
     */
    public List<String> createFunction(Viewer actor, AnkenScope scope, String epic, FunctionDraft draft, @Nullable String functionId) {
        calls.requireEnabled();
        TrackerContext ctx = context.load(scope);
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
        if (!ruleHasId(ctx, epic) && inTask.stream().anyMatch(f -> f.id().equals(name))) {
            throw new Conflict("Function " + name + " already exists in " + draft.parentKey(), "name");
        }
        FunctionPreview p = plan(ctx, epic, draft, functionId);
        String project = draft.parentKey().split("-", -1)[0];
        for (PreviewStep s : p.steps()) {
            if (!Text.hasText(ctx.members.get(s.assigneeId()).jiraAccountId())) {
                throw new Invalid(ctx.members.get(s.assigneeId()).name() + " has no Jira account", "assignee");
            }
        }
        String type = reader.issueTypes(project).stream().filter(JiraIssueType::subtask).map(JiraIssueType::id).findFirst()
                .orElseThrow(() -> new Invalid("Project " + project + " has no sub-task issue type", "parent"));
        List<String> keys = new ArrayList<>();
        try {
            for (PreviewStep s : p.steps()) {
                String acc = ctx.members.get(s.assigneeId()).jiraAccountId();
                JiraRequest r = JiraRequests.createSubtask(project, type, draft.parentKey(), s.summary(), acc, s.start(), s.end(), s.estimateH(),
                        ctx.settings.fieldMapping);
                keys.add(createdKey(calls.send(actor, JiraCalls.ADD_FUNCTION, draft.parentKey(), r)));
            }
        } finally {
            if (!keys.isEmpty()) {
                sync.refresh(keys);
            }
        }
        return keys;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The key in Jira's answer to a create. */
    private static String createdKey(JiraResponse r) {
        try {
            return JSON.readTree(r.body()).path("key").asText();
        } catch (JsonProcessingException e) {
            throw new JiraError("Jira returned invalid JSON for a created issue: " + e.getMessage(), e);
        }
    }
}
