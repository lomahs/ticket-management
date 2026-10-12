package tracker.application.usecases;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.Deps;
import tracker.application.SyncFromJira;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.ports.JiraError;
import tracker.application.ports.Repositories.SyncRun;
import tracker.application.ports.UnitOfWork;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.Dates;
import tracker.domain.Holiday;
import tracker.domain.IssueKind;
import tracker.domain.Member;
import tracker.domain.Naming;
import tracker.domain.Permission;
import tracker.domain.Py;
import tracker.domain.Role;
import tracker.domain.RuleError;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;

/** Settings: Jira connection and tracked epics, field/status mapping, name rules, members, calendar, hours, alerts. */
public final class AdminUseCases {
    private AdminUseCases() {
    }

    public static final List<String> INTERVALS = List.of("5", "15", "60", "manual");
    public static final List<String> STATUSES = List.of("N", "W", "R", "C");
    public static final List<String> COUNTRIES = List.of("VN", "JP");
    private static final Pattern HTTPS = Pattern.compile("^https://");
    private static final Pattern EPIC_KEY = Pattern.compile("[A-Z][A-Z0-9_]*-\\d+");

    private static TrackerSettings load(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            return u.settings().load();
        }
    }

    private static void save(Deps d, TrackerSettings s) {
        try (UnitOfWork u = d.uow().begin()) {
            u.settings().save(s);
            u.commit();
        }
    }

    // --- Jira connection & epics ------------------------------------------------------------------------------------

    public record EpicView(String key, String title, String project) {
    }

    public record JiraSettingsView(String url, String email, String tokenMasked, String interval, String status,
                                   List<EpicView> epics, Instant lastFullSync) {
    }

    public static JiraSettingsView jiraSettings(Deps d) {
        TrackerSettings s;
        Map<String, Anken> anken = new LinkedHashMap<>();
        SyncRun runsOk;
        try (UnitOfWork u = d.uow().begin()) {
            s = u.settings().load();
            u.anken().list().forEach(a -> anken.put(a.key(), a));
            runsOk = u.syncRuns().latestOk();
        }
        String token = d.secrets().decrypt(s.jira.tokenEncrypted);
        String masked = Py.truthy(token) ? token.substring(0, Math.min(12, token.length())) + "••••••••" : "";
        List<EpicView> epics = s.jira.epics.stream().map(k -> new EpicView(k,
                anken.containsKey(k) ? anken.get(k).title() : "",
                anken.containsKey(k) ? anken.get(k).project() : k.split("-", -1)[0])).toList();
        return new JiraSettingsView(s.jira.url, s.jira.email, masked, s.jira.interval, s.jira.status, epics,
                runsOk != null ? runsOk.finishedAt() : null);
    }

    /** @return ok | fail */
    public static String testJira(Deps d, String url, String email, String token) {
        String stored = d.secrets().decrypt(load(d).jira.tokenEncrypted);
        try {
            return d.readerFor().create(url.strip(), email.strip(), Py.or(token, stored)).testConnection() ? "ok" : "fail";
        } catch (JiraError e) {
            return "fail";
        }
    }

    private static String rstripSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    public static JiraSettingsView saveJira(Deps d, String url, String email, String token, String interval) {
        if (!INTERVALS.contains(interval)) {
            throw new Invalid("Sync interval must be one of " + String.join(", ", INTERVALS), "interval");
        }
        if (!HTTPS.matcher(url.strip()).lookingAt()) {
            throw new Invalid("Site URL must start with https://", "url");
        }
        TrackerSettings s = load(d);
        s.jira.url = rstripSlash(url.strip());
        s.jira.email = email.strip();
        s.jira.interval = interval;
        if (Py.truthy(token)) {
            s.jira.tokenEncrypted = d.secrets().encrypt(token);
        }
        s.jira.status = testJira(d, s.jira.url, s.jira.email, token);
        save(d, s);
        return jiraSettings(d);
    }

    public static JiraSettingsView addEpic(Deps d, String key) {
        String k = key.strip().toUpperCase(java.util.Locale.ROOT);
        if (!EPIC_KEY.matcher(k).matches()) {
            throw new Invalid("Enter an Epic key such as PAY-1100", "key");
        }
        TrackerSettings s = load(d);
        if (s.jira.epics.contains(k)) {
            throw new Conflict(k + " is already tracked", "key");
        }
        EpicSnapshot epic = d.reader().getEpic(k); // JiraError propagates: Jira being unreachable is not a problem with the key
        if (epic == null) {
            throw new NotFound(k + " was not found or is not an Epic", "key");
        }
        s.jira.epics.add(k);
        save(d, s);
        d.sync().run(true);
        return jiraSettings(d);
    }

    public static JiraSettingsView removeEpic(Deps d, String key) {
        TrackerSettings s = load(d);
        if (!s.jira.epics.contains(key)) {
            throw new NotFound(key + " is not tracked", "key");
        }
        s.jira.epics.remove(key);
        save(d, s);
        d.sync().run(true);
        return jiraSettings(d);
    }

    // --- field mapping ----------------------------------------------------------------------------------------------

    public static final Map<String, List<String>> TARGET_TYPES = targetTypes();

    private static Map<String, List<String>> targetTypes() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put("planned_start", List.of("date", "datetime"));
        t.put("planned_end", List.of("date", "datetime"));
        t.put("effort", List.of("number"));
        return java.util.Collections.unmodifiableMap(t);
    }

    public record FieldMappingView(String plannedStart, String plannedEnd, String effort, List<JiraField> catalog) {
    }

    /**
     * @param target planned_start | planned_end | effort
     * @param status ok | not_mapped | not_found | type_mismatch
     * @param value  date for dates, hours for effort
     */
    public record FieldCheck(String target, String fieldId, String status, Object value) {
    }

    public record FieldMappingCheck(String key, String summary, List<FieldCheck> results) {
    }

    private static List<JiraField> catalog(Deps d) {
        try {
            return d.reader().listFields();
        } catch (JiraError e) {
            return List.of();
        }
    }

    public static FieldMappingView fieldMapping(Deps d) {
        TrackerSettings.FieldMapping fm = load(d).fieldMapping;
        return new FieldMappingView(fm.plannedStart, fm.plannedEnd, fm.effort, catalog(d));
    }

    public static FieldMappingView saveFieldMapping(Deps d, Map<String, String> mapping) {
        TrackerSettings s = load(d);
        mapping.forEach((k, v) -> {
            if (TARGET_TYPES.containsKey(k)) {
                s.fieldMapping.set(k, v.strip());
            }
        });
        save(d, s);
        d.sync().run(true);
        return fieldMapping(d);
    }

    public static FieldMappingCheck checkFieldMapping(Deps d, String sampleKey, Map<String, String> mapping) {
        String key = sampleKey.strip().toUpperCase(java.util.Locale.ROOT);
        IssueSnapshot snap = Py.truthy(key) ? d.reader().getIssue(key) : null;
        if (snap == null) {
            throw new NotFound("Ticket " + Py.or(key, "(empty)") + " was not found", "sample");
        }
        TrackerSettings.FieldMapping fm = load(d).fieldMapping;
        Map<String, String> types = new LinkedHashMap<>();
        catalog(d).forEach(f -> types.put(f.id(), f.type()));
        List<FieldCheck> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : TARGET_TYPES.entrySet()) {
            String target = e.getKey();
            String raw0 = mapping.containsKey(target) ? mapping.get(target) : fm.get(target);
            String fid = (raw0 == null ? "" : raw0).strip();
            if (fid.isEmpty()) {
                out.add(new FieldCheck(target, fid, "not_mapped", null));
            } else if (!types.containsKey(fid)) {
                out.add(new FieldCheck(target, fid, "not_found", null));
            } else if (!e.getValue().contains(types.get(fid))) {
                out.add(new FieldCheck(target, fid, "type_mismatch", null));
            } else {
                IssueSnapshot fresh = snap.fields().containsKey(fid) ? snap : d.reader().getIssue(key);
                Object raw = fresh != null ? fresh.fields().get(fid) : null;
                Object value;
                if (!target.equals("effort")) {
                    String s = Py.truthy(raw) ? Py.str(raw) : null;
                    value = s != null && s.length() > 10 ? s.substring(0, 10) : s;
                } else if (Py.truthy(raw)) {
                    value = fid.equals("timeoriginalestimate") || fid.equals("aggregatetimeoriginalestimate")
                            ? Py.toFloat(raw) / 3600 : Py.toFloat(raw);
                } else {
                    value = null;
                }
                out.add(new FieldCheck(target, fid, "ok", value));
            }
        }
        return new FieldMappingCheck(key, snap.summary(), out);
    }

    // --- status mapping ---------------------------------------------------------------------------------------------

    /**
     * @param mapped    explicit mapping, if any
     * @param effective what the tracker uses
     */
    public record StatusRow(String name, String category, String mapped, String effective) {
    }

    public static List<StatusRow> statusMapping(Deps d) {
        TrackerSettings.StatusMapping sm = load(d).statusMapping;
        TreeSet<String> names = new TreeSet<>(sm.seen.keySet());
        names.addAll(sm.byName.keySet());
        return names.stream().map(n -> {
            String mapped = sm.byName.get(n);
            String effective = Py.truthy(mapped) ? mapped
                    : SyncFromJira.CATEGORY_STATUS.getOrDefault(sm.seen.getOrDefault(n, ""), Status.WIP).value();
            return new StatusRow(n, sm.seen.getOrDefault(n, ""), mapped, effective);
        }).toList();
    }

    public static List<StatusRow> saveStatusMapping(Deps d, Map<String, String> mapping) {
        if (mapping.values().stream().anyMatch(v -> Py.truthy(v) && !STATUSES.contains(v))) {
            throw new Invalid("Status must be one of " + String.join(", ", STATUSES), "mapping");
        }
        TrackerSettings s = load(d);
        Map<String, String> byName = new LinkedHashMap<>();
        mapping.forEach((k, v) -> {
            if (Py.truthy(v)) {
                byName.put(k, v);
            }
        });
        s.statusMapping.byName = byName;
        save(d, s);
        d.sync().run(true);
        return statusMapping(d);
    }

    // --- name rules -------------------------------------------------------------------------------------------------

    public record NameRulesView(String rule, Map<String, String> aliases, int matched, int total) {
    }

    public record NameRulePreview(String error, List<String> tokens, Map<String, String> values, String step,
                                  boolean sampleMatches, int matched, int total) {
    }

    private static List<String> summaries(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            List<String> epics = u.settings().load().jira.epics;
            return u.issues().list(epics).stream().filter(i -> i.kind() == IssueKind.SUBTASK).map(i -> i.summary()).toList();
        }
    }

    private static Map<StepKind, String> aliases(Map<String, String> raw, Map<StepKind, String> current) {
        if (raw == null) {
            return new LinkedHashMap<>(current);
        }
        Map<StepKind, String> out = new LinkedHashMap<>();
        try {
            raw.forEach((k, v) -> out.put(StepKind.of(k), v));
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage(), "aliases");
        }
        return out;
    }

    private static Map<String, String> aliasValues(Map<StepKind, String> aliases) {
        Map<String, String> out = new LinkedHashMap<>();
        aliases.forEach((k, v) -> out.put(k.value(), v));
        return out;
    }

    public static NameRulesView nameRules(Deps d) {
        TrackerSettings.NameRules nr = load(d).nameRules;
        List<String> summaries = summaries(d);
        return new NameRulesView(nr.rule, aliasValues(nr.aliases),
                Naming.coverage(Naming.compileRule(nr.rule, nr.aliases), summaries), summaries.size());
    }

    public static NameRulePreview previewNameRule(Deps d, String rule, Map<String, String> rawAliases, String sample) {
        List<String> summaries = summaries(d);
        Map<StepKind, String> al = aliases(rawAliases, load(d).nameRules.aliases);
        Naming.CompiledRule compiled;
        try {
            compiled = Naming.compileRule(rule, al);
        } catch (RuleError e) {
            return new NameRulePreview(e.getMessage(), List.of(), Map.of(), null, false, 0, summaries.size());
        }
        Naming.Parsed p = Naming.parseSummary(compiled, sample);
        return new NameRulePreview(null, compiled.tokens(), p != null ? p.values() : Map.of(), p != null ? p.step().value() : null,
                p != null, Naming.coverage(compiled, summaries), summaries.size());
    }

    public static NameRulesView saveNameRules(Deps d, String rule, Map<String, String> rawAliases) {
        TrackerSettings s = load(d);
        Map<StepKind, String> al = aliases(rawAliases, s.nameRules.aliases);
        String r = Py.or(rule, Naming.DEFAULT_RULE);
        try {
            Naming.compileRule(r, al);
        } catch (RuleError e) {
            throw new Invalid(e.getMessage(), "rule");
        }
        s.nameRules.rule = r;
        s.nameRules.aliases = al;
        save(d, s);
        d.sync().run(true);
        return nameRules(d);
    }

    // --- members ----------------------------------------------------------------------------------------------------

    public record MemberPatch(String name, Role role, Permission permission, String country, boolean clearRole) {
    }

    public static Member updateMember(Deps d, String memberId, MemberPatch p) {
        if (Py.truthy(p.country()) && !COUNTRIES.contains(p.country())) {
            throw new Invalid("Country must be one of " + String.join(", ", COUNTRIES), "country");
        }
        if (p.name() != null && p.name().strip().isEmpty()) {
            throw new Invalid("Name is required", "name");
        }
        try (UnitOfWork u = d.uow().begin()) {
            Member m = u.members().get(memberId);
            if (m == null) {
                throw new NotFound("Member " + memberId + " was not found");
            }
            m = new Member(m.id(), Py.truthy(p.name()) ? p.name().strip() : m.name(), m.fullName(),
                    p.clearRole() ? null : (p.role() != null ? p.role() : m.role()),
                    Py.or(p.country(), m.country()), m.color(), m.email(),
                    p.permission() != null ? p.permission() : m.permission(), m.external(), m.jiraAccountId());
            u.members().save(m);
            u.commit();
            return m;
        }
    }

    // --- calendar settings ------------------------------------------------------------------------------------------

    public static List<Holiday> holidays(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            List<Holiday> out = new ArrayList<>(u.calendar().holidays().values());
            out.sort(Comparator.comparing(Holiday::date));
            return out;
        }
    }

    public static List<Holiday> addHoliday(Deps d, String date, String name, List<String> countries) {
        if (name.strip().isEmpty()) {
            throw new Invalid("Name is required", "name");
        }
        if (countries.isEmpty() || countries.stream().anyMatch(c -> !COUNTRIES.contains(c))) {
            throw new Invalid("Choose Vietnam, Japan or both", "countries");
        }
        try (UnitOfWork u = d.uow().begin()) {
            u.calendar().saveHoliday(new Holiday(date, countries, name.strip()));
            u.commit();
        }
        return holidays(d);
    }

    public static List<Holiday> deleteHoliday(Deps d, String date) {
        try (UnitOfWork u = d.uow().begin()) {
            u.calendar().deleteHoliday(date);
            u.commit();
        }
        return holidays(d);
    }

    public static Map<String, String> extraDays(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            return new LinkedHashMap<>(new TreeMap<>(u.calendar().extraDays()));
        }
    }

    public static Map<String, String> addExtraDay(Deps d, String date, String note) {
        try (UnitOfWork u = d.uow().begin()) {
            List<Integer> days = u.settings().load().workingHours.days();
            if (days.contains(Dates.weekday(date))) {
                throw new Invalid(Dates.dm(date) + " is already a working day", "date");
            }
            if (u.calendar().extraDays().containsKey(date)) {
                throw new Conflict(Dates.dm(date) + " is already in the list", "date");
            }
            u.calendar().saveExtraDay(date, Py.or(note.strip(), "Extra working day"));
            u.commit();
        }
        return extraDays(d);
    }

    public static Map<String, String> deleteExtraDay(Deps d, String date) {
        try (UnitOfWork u = d.uow().begin()) {
            u.calendar().deleteExtraDay(date);
            u.commit();
        }
        return extraDays(d);
    }

    public static WorkingHours workingHours(Deps d) {
        return load(d).workingHours;
    }

    public static WorkingHours saveWorkingHours(Deps d, WorkingHours wh) {
        if (!(0 < wh.hoursPerDay() && wh.hoursPerDay() <= 24)) {
            throw new Invalid("Hours per working day must be between 0 and 24", "hours_per_day");
        }
        if (wh.timesheetToleranceH() < 0) {
            throw new Invalid("Tolerance cannot be negative", "timesheet_tolerance_h");
        }
        if (wh.days().isEmpty() || wh.days().stream().anyMatch(x -> x < 0 || x > 6)) {
            throw new Invalid("Choose at least one working day", "days");
        }
        TrackerSettings s = load(d);
        s.workingHours = new WorkingHours(wh.hoursPerDay(), List.copyOf(new TreeSet<>(wh.days())), wh.timesheetToleranceH());
        save(d, s);
        return s.workingHours;
    }

    public static Thresholds alerts(Deps d) {
        return load(d).thresholds;
    }

    public static Thresholds saveAlerts(Deps d, Thresholds t) {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("soon_days", t.soonDays());
        values.put("effort_warn_pct", t.effortWarnPct());
        values.put("held_days", t.heldDays());
        values.put("review_rounds", t.reviewRounds());
        values.put("capacity_pct", t.capacityPct());
        values.forEach((k, v) -> {
            if (v < 0) {
                throw new Invalid("Thresholds cannot be negative", k);
            }
        });
        TrackerSettings s = load(d);
        s.thresholds = t;
        save(d, s);
        return t;
    }
}
