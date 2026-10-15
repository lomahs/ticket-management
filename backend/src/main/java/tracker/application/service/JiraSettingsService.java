package tracker.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** What each mapped field must hold: planned dates are dates, effort a number. */
    static final Map<String, List<String>> TARGET_TYPES = Map.of(
            "planned_start", List.of("date", "datetime"),
            "planned_end", List.of("date", "datetime"),
            "effort", List.of("number"));
    private static final List<String> TARGETS = List.of("planned_start", "planned_end", "effort");

    public record FieldMappingView(String plannedStart, String plannedEnd, String effort, List<JiraField> catalog) {
    }

    /**
     * @param target planned_start | planned_end | effort
     * @param status ok | not_mapped | not_found | type_mismatch
     * @param value  the date for dates, hours for effort
     */
    public record FieldCheck(String target, String fieldId, String status, @Nullable Object value) {
    }

    public record FieldMappingCheck(String key, String summary, List<FieldCheck> results) {
    }

    private List<JiraField> catalog() {
        try {
            return reader.listFields();
        } catch (JiraError e) {
            return List.of();
        }
    }

    public FieldMappingView fieldMapping() {
        TrackerSettings.FieldMapping fm = settings.load().fieldMapping;
        return new FieldMappingView(fm.plannedStart, fm.plannedEnd, fm.effort, catalog());
    }

    /** @param mapping target → Jira field id ("" for none) */
    public FieldMappingView saveFieldMapping(Map<String, String> mapping) {
        settings.update(s -> mapping.forEach((target, fieldId) -> {
            if (TARGETS.contains(target)) {
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
        for (String target : TARGETS) {
            String fid = mapping.getOrDefault(target, fm.get(target)).strip();
            if (fid.isEmpty()) {
                out.add(new FieldCheck(target, fid, "not_mapped", null));
            } else if (!types.containsKey(fid)) {
                out.add(new FieldCheck(target, fid, "not_found", null));
            } else if (!TARGET_TYPES.get(target).contains(types.get(fid))) {
                out.add(new FieldCheck(target, fid, "type_mismatch", null));
            } else {
                // the sample was read with the saved fields; read it again when the field asked for is not among them
                IssueSnapshot fresh = snap.fields().containsKey(fid) ? snap : reader.findIssue(key).orElse(snap);
                Object raw = fresh.fields().get(fid);
                Object value = target.equals("effort") ? JiraValues.hours(raw, fid) : JiraValues.date(raw);
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

    private StructureView structureView(WorkStructure ws) {
        List<Issue> parents = new ArrayList<>(issues.findByEpics(settings.load().jira.epics).stream()
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
                .map(t -> new TaskTemplate(t.match().strip(), t.phase().strip(), t.prefix().strip(), t.workflow())).toList();
        Set<String> names = new HashSet<>();
        for (TaskTemplate t : tasks) {
            if (t.match().isEmpty()) {
                throw new Invalid("Task name is required", "tasks");
            }
            if (!phases.contains(t.phase())) {
                throw new Invalid(t.match() + ": phase " + (t.phase().isEmpty() ? "(empty)" : t.phase()) + " is not in the phase list",
                        "tasks");
            }
            if (!names.add(WorkStructure.normalise(t.match()))) {
                throw new Invalid("Each Task name may appear only once: " + t.match(), "tasks");
            }
        }
        return new WorkStructure(phases, tasks);
    }

    public StructureView structure() {
        return structureView(settings.load().structure);
    }

    /** Classifies the Tasks with unsaved settings, so the screen can show matches while the admin types. */
    public StructureView previewStructure(WorkStructure draft) {
        return structureView(draft);
    }

    public StructureView saveStructure(WorkStructure draft) {
        WorkStructure ws = checked(draft);
        settings.update(s -> s.structure = ws);
        sync.run(true);
        return structure();
    }

    // --- name rules -------------------------------------------------------------------------------------------------

    /** @param matched sub-task summaries the rule reads */
    public record NameRulesView(String rule, Map<String, String> aliases, int matched, int total) {
    }

    public record NameRulePreview(@Nullable String error, List<String> tokens, Map<String, String> values, @Nullable String step,
                                  boolean sampleMatches, int matched, int total) {
    }

    private List<String> summaries() {
        return issues.findByEpics(settings.load().jira.epics).stream().filter(i -> i.kind() == IssueKind.SUBTASK)
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

    public NameRulesView nameRules() {
        TrackerSettings.NameRules nr = settings.load().nameRules;
        List<String> summaries = summaries();
        return new NameRulesView(nr.rule, aliasValues(nr.aliases), Naming.coverage(Naming.compileRule(nr.rule, nr.aliases), summaries),
                summaries.size());
    }

    /** @param rawAliases null uses the saved aliases */
    public NameRulePreview previewNameRule(String rule, @Nullable Map<String, String> rawAliases, String sample) {
        List<String> summaries = summaries();
        Map<StepKind, String> al = aliases(rawAliases, settings.load().nameRules.aliases);
        Naming.CompiledRule compiled;
        try {
            compiled = Naming.compileRule(rule, al);
            Naming.checkIdentity(compiled);
        } catch (RuleError e) {
            return new NameRulePreview(e.getMessage(), List.of(), Map.of(), null, false, 0, summaries.size());
        }
        Naming.Parsed p = Naming.parseSummary(compiled, sample);
        return new NameRulePreview(null, compiled.tokens(), p != null ? p.values() : Map.of(), p != null ? p.step().value() : null,
                p != null, Naming.coverage(compiled, summaries), summaries.size());
    }

    /** @param rule "" restores the default rule */
    public NameRulesView saveNameRules(String rule, Map<String, String> rawAliases) {
        Map<StepKind, String> al = aliases(rawAliases, Map.of());
        String r = Text.orElse(rule, Naming.DEFAULT_RULE);
        try {
            Naming.checkIdentity(Naming.compileRule(r, al));
        } catch (RuleError e) {
            throw new Invalid(e.getMessage(), "rule");
        }
        settings.update(s -> {
            s.nameRules.rule = r;
            s.nameRules.aliases = al;
        });
        sync.run(true);
        return nameRules();
    }
}
