package tracker.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.JiraValues;
import tracker.application.SyncService;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraError;
import tracker.application.dto.EpicSnapshot;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraReaderFactory;
import tracker.application.ports.SecretBox;
import tracker.application.ports.SyncRunRepository.SyncRun;
import tracker.application.ports.SyncRunRepository;
import tracker.application.settings.SettingsStore;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.Naming;
import tracker.domain.RuleError;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.StepWeights;
import tracker.domain.TaskNames;
import tracker.domain.TaskTemplate;
import tracker.domain.Text;
import tracker.domain.WorkStructure;

/**
 * Settings that decide what is read from Jira: the connection and tracked epics, the field and status mappings, and the
 * Summary naming rule. Changes that alter the synced data run a full sync afterwards.
 */
@Service
public class JiraSettingsService {
    public static final List<String> INTERVALS = List.of("5", "15", "60", "manual");
    private static final Pattern EPIC_KEY = Pattern.compile("[A-Z][A-Z0-9_]*-\\d+");

    private final SettingsStore settings;
    private final AnkenRepository anken;
    private final IssueRepository issues;
    private final SyncRunRepository runs;
    private final JiraReader reader;
    private final JiraReaderFactory readers;
    private final SecretBox secrets;
    private final SyncService sync;

    public JiraSettingsService(SettingsStore settings, AnkenRepository anken, IssueRepository issues, SyncRunRepository runs,
                               JiraReader reader, JiraReaderFactory readers, SecretBox secrets, SyncService sync) {
        this.settings = settings;
        this.anken = anken;
        this.issues = issues;
        this.runs = runs;
        this.reader = reader;
        this.readers = readers;
        this.secrets = secrets;
        this.sync = sync;
    }

    // --- Jira connection & epics ------------------------------------------------------------------------------------

    public record EpicView(String key, String title, String project) {
    }

    /** @param status result of the last connection test: ok | fail | null */
    public record JiraSettingsView(String url, String email, String tokenMasked, String interval, @Nullable String status,
                                   List<EpicView> epics, @Nullable Instant lastFullSync) {
    }

    private String token(TrackerSettings s) {
        return Text.hasText(s.jira.tokenEncrypted) ? secrets.decrypt(s.jira.tokenEncrypted) : "";
    }

    public JiraSettingsView jiraSettings() {
        TrackerSettings s = settings.load();
        Map<String, Anken> known = new LinkedHashMap<>();
        anken.findAll().forEach(a -> known.put(a.key(), a));
        String token = token(s);
        String masked = token.isEmpty() ? "" : token.substring(0, Math.min(12, token.length())) + "••••••••";
        List<EpicView> epics = s.jira.epics.stream().map(k -> new EpicView(k,
                known.containsKey(k) ? known.get(k).title() : "",
                known.containsKey(k) ? known.get(k).project() : k.split("-", -1)[0])).toList();
        return new JiraSettingsView(s.jira.url, s.jira.email, masked, s.jira.interval, s.jira.status, epics,
                runs.findLatestOk().map(SyncRun::finishedAt).orElse(null));
    }

    /**
     * @param token null tests with the stored token
     * @return ok | fail
     */
    public String testJira(String url, String email, @Nullable String token) {
        String effective = Text.hasText(token) ? token : token(settings.load());
        try {
            return readers.create(url.strip(), email.strip(), effective).testConnection() ? "ok" : "fail";
        } catch (JiraError e) {
            return "fail";
        }
    }

    /** @param token a new API token, or null to keep the stored one */
    public JiraSettingsView saveJira(String url, String email, @Nullable String token, String interval) {
        if (!INTERVALS.contains(interval)) {
            throw new Invalid("Sync interval must be one of " + String.join(", ", INTERVALS), "interval");
        }
        String site = url.strip().replaceAll("/+$", "");
        if (!site.startsWith("https://")) {
            throw new Invalid("Site URL must start with https://", "url");
        }
        String status = testJira(site, email, token);
        settings.update(s -> {
            s.jira.url = site;
            s.jira.email = email.strip();
            s.jira.interval = interval;
            if (Text.hasText(token)) {
                s.jira.tokenEncrypted = secrets.encrypt(token);
            }
            s.jira.status = status;
        });
        return jiraSettings();
    }

    /** An epic as Jira has it, and whether the app tracks it. */
    public record EpicLookup(String key, String title, String project, boolean tracked) {
    }

    public EpicLookup lookupEpic(String key) {
        String k = key.strip().toUpperCase(Locale.ROOT);
        if (!EPIC_KEY.matcher(k).matches()) {
            throw new Invalid("Enter an Epic key such as PAY-1100", "key");
        }
        EpicSnapshot e = reader.findEpic(k).orElseThrow(() -> new NotFound(k + " was not found or is not an Epic", "key"));
        return new EpicLookup(k, e.summary(), e.project(), settings.load().jira.epics.contains(k));
    }

    public JiraSettingsView addEpic(String key) {
        String k = key.strip().toUpperCase(Locale.ROOT);
        if (!EPIC_KEY.matcher(k).matches()) {
            throw new Invalid("Enter an Epic key such as PAY-1100", "key");
        }
        if (settings.load().jira.epics.contains(k)) {
            throw new Conflict(k + " is already tracked", "key");
        }
        // JiraError propagates: Jira being unreachable is not a problem with the key
        reader.findEpic(k).orElseThrow(() -> new NotFound(k + " was not found or is not an Epic", "key"));
        settings.update(s -> {
            if (!s.jira.epics.contains(k)) {
                s.jira.epics.add(k);
            }
            s.anken.computeIfAbsent(k, e -> TrackerSettings.AnkenSettings.copyOf(s.structure, s.nameRules));
        });
        sync.run(true);
        return jiraSettings();
    }

    public JiraSettingsView removeEpic(String key) {
        if (!settings.load().jira.epics.contains(key)) {
            throw new NotFound(key + " is not tracked", "key");
        }
        settings.update(s -> s.jira.epics.remove(key));
        sync.run(true);
        return jiraSettings();
    }

    // --- field mapping ----------------------------------------------------------------------------------------------

    /**
     * A Jira field the tracker reads. The mappable ones are picked by the admin; the others are Jira system fields the
     * tracker always reads by their fixed id.
     *
     * @param types   Jira schema types the field must have; empty when any will do
     * @param fieldId the mapped Jira field id ("" for none) or the fixed one
     */
    public record AppField(String key, String label, String usedFor, String expects, List<String> types, String fieldId,
                           boolean mappable) {
    }

    private record Target(String key, String label, String usedFor, String expects, List<String> types) {
    }

    private static final List<String> DATE = List.of("date", "datetime");
    private static final List<String> NUMBER = List.of("number");
    /** The mappable fields, in the order they are listed. */
    private static final List<Target> TARGETS = List.of(
            new Target("planned_start", "Planned Start", "Planning, late flags and the matrix dates of sub-tasks", "Date", DATE),
            new Target("planned_end", "Planned End", "Due dates, late and soon flags, forecasts", "Date", DATE),
            new Target("effort", "Planned effort", "Estimate of a sub-task: effort, capacity and over-estimate flags", "Hours", NUMBER),
            new Target("remaining", "Remaining estimate", "Phase progress: logged / (logged + remaining)", "Hours", NUMBER));
    /** Jira system fields the tracker reads by their fixed id. */
    private static final List<AppField> FIXED = List.of(
            new AppField("summary", "Summary", "Phase, function and step of a sub-task, by the naming rule", "Text", List.of(), "summary", false),
            new AppField("status", "Status", "NYS / WIP / Resolved / Closed through the status mapping", "Status", List.of(), "status", false),
            new AppField("assignee", "Assignee", "Who handles the sub-task: My Work, workload, team members", "User", List.of(), "assignee", false),
            new AppField("reporter", "Reporter", "BAL of a function: the first reporter of its Create sub-task", "User", List.of(), "reporter", false),
            new AppField("parent", "Parent", "Epic → Task → Sub-task structure and phase groups", "Issue", List.of(), "parent", false),
            new AppField("worklog", "Work log", "Logged hours: actual effort, progress and timesheets; actual dates when the history has none", "Work log", List.of(), "worklog", false),
            new AppField("changelog", "History", "Actual start (moved to In Progress) and end (moved to Resolved), review rounds, days held in a status", "Changelog", List.of(), "changelog", false),
            new AppField("duedate", "Due date (epic)", "Plan date of an anken", "Date", List.of(), "duedate", false),
            new AppField("issuetype", "Issue type (epic)", "Checks that a tracked key is an Epic", "Issue type", List.of(), "issuetype", false),
            new AppField("project", "Project", "Project of an anken", "Project", List.of(), "project", false),
            new AppField("created", "Created", "Ticket creation date", "Date", List.of(), "created", false),
            new AppField("updated", "Updated", "Incremental sync: what changed since the last run", "Date", List.of(), "updated", false));

    /** @param fields every Jira field the tracker reads, the mappable ones first */
    public record FieldMappingView(List<AppField> fields, List<JiraField> catalog) {
    }

    /**
     * @param target planned_start | planned_end | effort | remaining
     * @param status ok | not_mapped | not_found | type_mismatch
     * @param value  the date for dates, hours for effort and remaining
     */
    public record FieldCheck(String target, String fieldId, String status, @Nullable Object value) {
    }

    public record FieldMappingCheck(String key, String summary, List<FieldCheck> results) {
    }

    /** Every field of the Jira site, by name. */
    private List<JiraField> catalog() {
        try {
            return reader.listFields().stream()
                    .sorted(Comparator.comparing((JiraField f) -> f.name().toLowerCase(Locale.ROOT)).thenComparing(JiraField::id)).toList();
        } catch (JiraError e) {
            return List.of();
        }
    }

    public FieldMappingView fieldMapping() {
        TrackerSettings.FieldMapping fm = settings.load().fieldMapping;
        List<AppField> fields = new ArrayList<>(TARGETS.stream()
                .map(t -> new AppField(t.key(), t.label(), t.usedFor(), t.expects(), t.types(), fm.get(t.key()), true)).toList());
        fields.addAll(FIXED);
        return new FieldMappingView(fields, catalog());
    }

    /** @param mapping target → Jira field id ("" for none) */
    public FieldMappingView saveFieldMapping(Map<String, String> mapping) {
        settings.update(s -> mapping.forEach((target, fieldId) -> {
            if (TARGETS.stream().anyMatch(t -> t.key().equals(target))) {
                s.fieldMapping.set(target, fieldId.strip());
            }
        }));
        sync.run(true);
        return fieldMapping();
    }

    /** Reads a sample issue with the given field ids (targets left out use the saved ones). */
    public FieldMappingCheck checkFieldMapping(String sampleKey, Map<String, String> mapping) {
        String key = sampleKey.strip().toUpperCase(Locale.ROOT);
        IssueSnapshot snap = (key.isEmpty() ? java.util.Optional.<IssueSnapshot>empty() : reader.findIssue(key))
                .orElseThrow(() -> new NotFound("Ticket " + Text.orElse(key, "(empty)") + " was not found", "sample"));
        TrackerSettings.FieldMapping fm = settings.load().fieldMapping;
        Map<String, String> types = new LinkedHashMap<>();
        catalog().forEach(f -> types.put(f.id(), f.type()));
        List<FieldCheck> out = new ArrayList<>();
        for (Target t : TARGETS) {
            String target = t.key();
            String fid = mapping.getOrDefault(target, fm.get(target)).strip();
            if (fid.isEmpty()) {
                out.add(new FieldCheck(target, fid, "not_mapped", null));
            } else if (!types.containsKey(fid)) {
                out.add(new FieldCheck(target, fid, "not_found", null));
            } else if (!t.types().contains(types.get(fid))) {
                out.add(new FieldCheck(target, fid, "type_mismatch", null));
            } else {
                // the sample was read with the saved fields; read it again when the field asked for is not among them
                IssueSnapshot fresh = snap.fields().containsKey(fid) ? snap : reader.findIssue(key).orElse(snap);
                Object raw = fresh.fields().get(fid);
                Object value = NUMBER.equals(t.types()) ? JiraValues.hours(raw, fid) : JiraValues.date(raw);
                out.add(new FieldCheck(target, fid, "ok", value));
            }
        }
        return new FieldMappingCheck(key, snap.summary(), out);
    }

    // --- status mapping ---------------------------------------------------------------------------------------------

    /**
     * @param mapped    the admin's mapping, if any
     * @param effective the status the tracker uses
     */
    public record StatusRow(String name, String category, @Nullable String mapped, String effective) {
    }

    public List<StatusRow> statusMapping() {
        TrackerSettings s = settings.load();
        TrackerSettings.StatusMapping sm = s.statusMapping;
        TreeSet<String> names = new TreeSet<>(sm.seen.keySet());
        names.addAll(sm.byName.keySet());
        return names.stream().map(n -> new StatusRow(n, sm.seen.getOrDefault(n, ""), sm.byName.get(n),
                SyncService.status(s, n, null).value())).toList();
    }

    /** @param mapping Jira status name → tracker status, or null to use the status category */
    public List<StatusRow> saveStatusMapping(Map<String, @Nullable Status> mapping) {
        Map<String, String> byName = new LinkedHashMap<>();
        mapping.forEach((name, status) -> {
            if (status != null) {
                byName.put(name, status.value());
            }
        });
        settings.update(s -> s.statusMapping.byName = byName);
        sync.run(true);
        return statusMapping();
    }

    // --- phases & tasks ---------------------------------------------------------------------------------------------

    /** @param template index of the matching Task template; null when none matches */
    public record SeenTask(String key, String epicKey, String summary, String phase, @Nullable Integer template) {
    }

    /** @param seen the tracked epics' Tasks, classified with these phases and tasks */
    public record StructureView(List<String> phases, List<TaskTemplate> tasks, List<SeenTask> seen) {
    }

    /** The epic a request names: null for the default (blank), else a tracked epic. */
    private @Nullable String tracked(@Nullable String epic) {
        if (epic == null || epic.isBlank()) {
            return null;
        }
        if (!settings.load().jira.epics.contains(epic)) {
            throw new NotFound("Anken " + epic + " is not tracked", "anken");
        }
        return epic;
    }

    /** The epics whose issues a view counts: the named one, or every tracked epic for the default. */
    private List<String> scopeOf(TrackerSettings s, @Nullable String epic) {
        return epic == null ? s.jira.epics : List.of(epic);
    }

    private StructureView structureView(WorkStructure ws, @Nullable String epic) {
        List<Issue> parents = new ArrayList<>(issues.findByEpics(scopeOf(settings.load(), epic)).stream()
                .filter(i -> i.kind() == IssueKind.PARENT).toList());
        parents.sort(Comparator.comparing(Issue::epicKey).thenComparing(Issue::key));
        List<SeenTask> seen = parents.stream().map(p -> {
            int t = ws.templateIndex(p.summary());
            return new SeenTask(p.key(), p.epicKey(), p.summary(), ws.phaseOfTask(p.summary()), t < 0 ? null : t);
        }).toList();
        return new StructureView(ws.phases(), ws.tasks(), seen);
    }

    private static WorkStructure checked(WorkStructure ws) {
        List<String> phases = ws.phases().stream().map(p -> p == null ? "" : p.strip()).toList();
        if (phases.stream().anyMatch(String::isEmpty)) {
            throw new Invalid("Phase name is required", "phases");
        }
        if (new HashSet<>(phases).size() != phases.size()) {
            throw new Invalid("Phase names must be unique", "phases");
        }
        List<TaskTemplate> tasks = ws.tasks().stream()
                .map(t -> new TaskTemplate(t.match().strip(), t.phase().strip(), t.prefix().strip(), t.workflow(), t.type().strip())).toList();
        Set<String> names = new HashSet<>();
        for (TaskTemplate t : tasks) {
            if (t.match().isEmpty()) {
                throw new Invalid("Task name is required", "tasks");
            }
            if (!phases.contains(t.phase())) {
                throw new Invalid(t.match() + ": phase " + (t.phase().isEmpty() ? "(empty)" : t.phase()) + " is not in the phase list",
                        "tasks");
            }
            if (!TaskTemplate.TYPES.contains(t.type())) {
                throw new Invalid(t.match() + ": type " + t.type() + " is not qna, bug or work_request", "tasks");
            }
            if (!names.add(WorkStructure.normalise(t.match()))) {
                throw new Invalid("Each Task name may appear only once: " + t.match(), "tasks");
            }
        }
        return new WorkStructure(phases, tasks);
    }

    public StructureView structure(@Nullable String epic) {
        String e = tracked(epic);
        return structureView(settings.load().structureOf(e), e);
    }

    /** Classifies the Tasks with unsaved settings, so the screen can show matches while the admin types. */
    public StructureView previewStructure(@Nullable String epic, WorkStructure draft) {
        return structureView(draft, tracked(epic));
    }

    /** @param epic the anken to save for; null or blank saves the default for new anken */
    public StructureView saveStructure(@Nullable String epic, WorkStructure draft) {
        String e = tracked(epic);
        WorkStructure ws = checked(draft);
        settings.update(s -> {
            if (e == null) {
                s.structure = ws;
            } else {
                ankenSettings(s, e).structure = ws;
            }
        });
        sync.run(true);
        return structure(e);
    }

    /** @param weights step key ("create", "balr", …) → weight; {@code own}: the anken has its own, not the default */
    public record StepWeightsView(Map<String, Double> weights, boolean own) {
    }

    private StepWeightsView stepWeightsView(TrackerSettings s, @Nullable String epic) {
        Map<String, Double> w = new LinkedHashMap<>();
        StepWeights sw = s.stepWeightsOf(epic);
        for (StepKind k : StepKind.values()) {
            w.put(k.value(), sw.of(k));
        }
        TrackerSettings.AnkenSettings a = epic == null ? null : s.anken.get(epic);
        return new StepWeightsView(w, a != null && a.stepWeights != null);
    }

    /** The default when {@code epic} is null or blank. */
    public StepWeightsView stepWeights(@Nullable String epic) {
        return stepWeightsView(settings.load(), tracked(epic));
    }

    /** @param raw every step key → a weight of 0 or more */
    public StepWeightsView saveStepWeights(@Nullable String epic, Map<String, Double> raw) {
        String scope = tracked(epic);
        Map<StepKind, Double> w = new EnumMap<>(StepKind.class);
        for (StepKind k : StepKind.values()) {
            Double v = raw.get(k.value());
            if (v == null || !(v >= 0)) {
                throw new Invalid("Every step needs a weight of 0 or more", "weights." + k.value());
            }
            w.put(k, v);
        }
        StepWeights sw = new StepWeights(w);
        TrackerSettings saved = settings.update(s -> {
            if (scope == null) {
                s.stepWeights = sw;
            } else {
                ankenSettings(s, scope).stepWeights = sw;
            }
        });
        return stepWeightsView(saved, scope);
    }

    /** The anken uses the default weights again. */
    public StepWeightsView resetStepWeights(String epic) {
        String scope = tracked(epic);
        TrackerSettings saved = settings.update(s -> {
            TrackerSettings.AnkenSettings a = scope == null ? null : s.anken.get(scope);
            if (a != null) {
                a.stepWeights = null;
            }
        });
        return stepWeightsView(saved, scope);
    }

    private static TrackerSettings.AnkenSettings ankenSettings(TrackerSettings s, String epic) {
        return s.anken.computeIfAbsent(epic, k -> TrackerSettings.AnkenSettings.copyOf(s.structure, s.nameRules));
    }

    public record AnkenSettingsView(StructureView structure, NameRulesView nameRules) {
    }

    /**
     * Copies the Phases & Tasks and the name rules of {@code from} onto {@code epic}, then runs a full sync.
     *
     * @param from "default" or a tracked epic key
     */
    public AnkenSettingsView copySettings(String epic, String from) {
        String target = tracked(epic);
        if (target == null) {
            throw new NotFound("Anken " + epic + " is not tracked", "anken");
        }
        if (from.equals(epic)) {
            throw new Invalid("Pick another anken to copy from", "from");
        }
        String source = "default".equals(from) ? null : tracked(from);
        settings.update(s -> {
            TrackerSettings.AnkenSettings copy = TrackerSettings.AnkenSettings.copyOf(s.structureOf(source), s.nameRulesOf(source));
            s.anken.put(target, copy);
        });
        sync.run(true);
        return new AnkenSettingsView(structure(target), nameRules(target));
    }

    // --- name rules -------------------------------------------------------------------------------------------------

    /** @param matched sub-task summaries the rule reads */
    /** @param taskRule how onboarding names the template Tasks it creates */
    public record NameRulesView(String rule, Map<String, String> aliases, int matched, int total, String taskRule) {
    }

    public record NameRulePreview(@Nullable String error, List<String> tokens, Map<String, String> values, @Nullable String step,
                                  boolean sampleMatches, int matched, int total) {
    }

    private List<String> summaries(@Nullable String epic) {
        return issues.findByEpics(scopeOf(settings.load(), epic)).stream().filter(i -> i.kind() == IssueKind.SUBTASK)
                .map(Issue::summary).toList();
    }

    /** Step key ("create", "balr", …) → names, as the API sends them. */
    private static Map<StepKind, String> aliases(@Nullable Map<String, String> raw, Map<StepKind, String> current) {
        if (raw == null) {
            return new LinkedHashMap<>(current);
        }
        Map<StepKind, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            try {
                out.put(StepKind.of(k), v);
            } catch (IllegalArgumentException e) {
                throw new Invalid("Unknown step " + k, "aliases");
            }
        });
        return out;
    }

    private static Map<String, String> aliasValues(Map<StepKind, String> aliases) {
        Map<String, String> out = new LinkedHashMap<>();
        aliases.forEach((k, v) -> out.put(k.value(), v));
        return out;
    }

    public NameRulesView nameRules(@Nullable String epic) {
        String e = tracked(epic);
        TrackerSettings.NameRules nr = settings.load().nameRulesOf(e);
        List<String> summaries = summaries(e);
        return new NameRulesView(nr.rule, aliasValues(nr.aliases), Naming.coverage(Naming.compileRule(nr.rule, nr.aliases), summaries),
                summaries.size(), Text.orElse(nr.taskRule, TaskNames.DEFAULT_RULE));
    }

    /** @param rawAliases null uses the saved aliases */
    public NameRulePreview previewNameRule(@Nullable String epic, String rule, @Nullable Map<String, String> rawAliases, String sample) {
        String scope = tracked(epic);
        List<String> summaries = summaries(scope);
        Map<StepKind, String> al = aliases(rawAliases, settings.load().nameRulesOf(scope).aliases);
        Naming.CompiledRule compiled;
        try {
            compiled = Naming.compileRule(rule, al);
            Naming.checkIdentity(compiled);
        } catch (RuleError e) {
            return new NameRulePreview(e.getMessage(), List.of(), Map.of(), null, false, 0, summaries.size());
        }
        Naming.Parsed p = Naming.parseSummary(compiled, sample);
        return new NameRulePreview(null, compiled.tokens(), p != null ? p.values() : Map.of(), p != null && p.step() != null ? p.step().value() : null,
                p != null, Naming.coverage(compiled, summaries), summaries.size());
    }

    /** @param rule "" restores the default rule */
    /** @param taskRule null keeps the saved Task name rule */
    public NameRulesView saveNameRules(@Nullable String epic, String rule, Map<String, String> rawAliases, @Nullable String taskRule) {
        String scope = tracked(epic);
        Map<StepKind, String> al = aliases(rawAliases, Map.of());
        String r = Text.orElse(rule, Naming.DEFAULT_RULE);
        try {
            Naming.checkIdentity(Naming.compileRule(r, al));
        } catch (RuleError e) {
            throw new Invalid(e.getMessage(), "rule");
        }
        String tr = taskRule == null ? null : Text.orElse(taskRule.strip(), TaskNames.DEFAULT_RULE);
        if (tr != null) {
            try {
                TaskNames.check(tr);
            } catch (RuleError e) {
                throw new Invalid(e.getMessage(), "task_rule");
            }
        }
        settings.update(s -> {
            TrackerSettings.NameRules nr = scope == null ? s.nameRules : ankenSettings(s, scope).nameRules;
            nr.rule = r;
            nr.aliases = al;
            if (tr != null) {
                nr.taskRule = tr;
            }
        });
        sync.run(true);
        return nameRules(scope);
    }
}
