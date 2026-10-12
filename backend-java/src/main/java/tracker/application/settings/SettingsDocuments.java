package tracker.application.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tracker.domain.StepKind;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;

/** (De)serialisation of the settings to the JSON documents the SettingsRepository stores, one per section. */
public final class SettingsDocuments {
    private SettingsDocuments() {
    }

    public static final List<String> SECTIONS = List.of(
            "jira", "field_mapping", "name_rules", "status_mapping", "thresholds", "working_hours", "default_country");

    /** Section → JSON-ready value (maps, lists, strings, numbers). */
    public static Map<String, Object> toDocuments(TrackerSettings s) {
        Map<String, Object> d = new LinkedHashMap<>();
        Map<String, Object> jira = new LinkedHashMap<>();
        jira.put("url", s.jira.url);
        jira.put("email", s.jira.email);
        jira.put("token_encrypted", s.jira.tokenEncrypted);
        jira.put("interval", s.jira.interval);
        jira.put("epics", new ArrayList<>(s.jira.epics));
        jira.put("status", s.jira.status);
        d.put("jira", jira);
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("planned_start", s.fieldMapping.plannedStart);
        fm.put("planned_end", s.fieldMapping.plannedEnd);
        fm.put("effort", s.fieldMapping.effort);
        d.put("field_mapping", fm);
        Map<String, Object> aliases = new LinkedHashMap<>();
        s.nameRules.aliases.forEach((k, v) -> aliases.put(k.value(), v));
        Map<String, Object> nr = new LinkedHashMap<>();
        nr.put("rule", s.nameRules.rule);
        nr.put("aliases", aliases);
        d.put("name_rules", nr);
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("by_name", new LinkedHashMap<>(s.statusMapping.byName));
        sm.put("seen", new LinkedHashMap<>(s.statusMapping.seen));
        d.put("status_mapping", sm);
        Thresholds t = s.thresholds;
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("soon_days", t.soonDays());
        th.put("effort_warn_pct", t.effortWarnPct());
        th.put("held_days", t.heldDays());
        th.put("review_rounds", t.reviewRounds());
        th.put("capacity_pct", t.capacityPct());
        d.put("thresholds", th);
        WorkingHours w = s.workingHours;
        Map<String, Object> wh = new LinkedHashMap<>();
        wh.put("hours_per_day", w.hoursPerDay());
        wh.put("days", new ArrayList<>(w.days()));
        wh.put("timesheet_tolerance_h", w.timesheetToleranceH());
        d.put("working_hours", wh);
        d.put("default_country", s.defaultCountry);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    private static String str(Map<String, Object> m, String k, String fallback) {
        return m.containsKey(k) ? (String) m.get(k) : fallback;
    }

    private static double num(Map<String, Object> m, String k, double fallback) {
        return m.get(k) instanceof Number n ? n.doubleValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    public static TrackerSettings fromDocuments(Map<String, Object> docs) {
        TrackerSettings s = new TrackerSettings();
        if (docs.containsKey("jira")) {
            Map<String, Object> j = map(docs.get("jira"));
            TrackerSettings.JiraConnection c = s.jira;
            c.url = str(j, "url", c.url);
            c.email = str(j, "email", c.email);
            c.tokenEncrypted = str(j, "token_encrypted", c.tokenEncrypted);
            c.interval = str(j, "interval", c.interval);
            if (j.get("epics") instanceof List<?> epics) {
                c.epics = new ArrayList<>((List<String>) epics);
            }
            c.status = str(j, "status", c.status);
        }
        if (docs.containsKey("field_mapping")) {
            Map<String, Object> f = map(docs.get("field_mapping"));
            TrackerSettings.FieldMapping m = s.fieldMapping;
            m.plannedStart = str(f, "planned_start", m.plannedStart);
            m.plannedEnd = str(f, "planned_end", m.plannedEnd);
            m.effort = str(f, "effort", m.effort);
        }
        if (docs.containsKey("name_rules")) {
            Map<String, Object> nr = map(docs.get("name_rules"));
            s.nameRules.rule = (String) nr.get("rule");
            Map<StepKind, String> aliases = new LinkedHashMap<>();
            map(nr.get("aliases")).forEach((k, v) -> aliases.put(StepKind.of(k), (String) v));
            s.nameRules.aliases = aliases;
        }
        if (docs.containsKey("status_mapping")) {
            Map<String, Object> sm = map(docs.get("status_mapping"));
            if (sm.get("by_name") instanceof Map<?, ?> byName) {
                s.statusMapping.byName = new LinkedHashMap<>((Map<String, String>) byName);
            }
            if (sm.get("seen") instanceof Map<?, ?> seen) {
                s.statusMapping.seen = new LinkedHashMap<>((Map<String, String>) seen);
            }
        }
        if (docs.containsKey("thresholds")) {
            Map<String, Object> t = map(docs.get("thresholds"));
            Thresholds d = s.thresholds;
            s.thresholds = new Thresholds(num(t, "soon_days", d.soonDays()), num(t, "effort_warn_pct", d.effortWarnPct()),
                    num(t, "held_days", d.heldDays()), num(t, "review_rounds", d.reviewRounds()),
                    num(t, "capacity_pct", d.capacityPct()));
        }
        if (docs.containsKey("working_hours")) {
            Map<String, Object> w = map(docs.get("working_hours"));
            WorkingHours d = s.workingHours;
            List<Integer> days = w.get("days") instanceof List<?> l
                    ? l.stream().map(x -> ((Number) x).intValue()).toList() : d.days();
            s.workingHours = new WorkingHours(num(w, "hours_per_day", d.hoursPerDay()), days,
                    num(w, "timesheet_tolerance_h", d.timesheetToleranceH()));
        }
        if (docs.containsKey("default_country")) {
            s.defaultCountry = (String) docs.get("default_country");
        }
        return s;
    }
}
