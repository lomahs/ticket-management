package tracker.adapters.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.Deps;
import tracker.application.usecases.AdminUseCases;
import tracker.application.usecases.AdminUseCases.FieldMappingCheck;
import tracker.application.usecases.AdminUseCases.FieldMappingView;
import tracker.application.usecases.AdminUseCases.JiraSettingsView;
import tracker.application.usecases.AdminUseCases.MemberPatch;
import tracker.application.usecases.AdminUseCases.NameRulePreview;
import tracker.application.usecases.AdminUseCases.NameRulesView;
import tracker.application.usecases.AdminUseCases.StatusRow;
import tracker.domain.Holiday;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;

@RestController
@RequestMapping("/api/v1")
public class SettingsApi {
    private static final List<String> STATUS_CHOICES = List.of("N", "W", "R", "C");
    private static final List<String> COUNTRY_CHOICES = List.of("VN", "JP");
    private final Deps d;

    public SettingsApi(Deps d) {
        this.d = d;
    }

    public record JiraTestOut(String status) {
    }

    /** Reads the body; throws the body error at once when there is no usable object. */
    private static Input.Obj body(Input in, byte[] raw) {
        Input.Obj b = in.body(raw);
        if (b == null) {
            in.check();
        }
        return b;
    }

    // --- Jira connection & epics ------------------------------------------------------------------------------------

    @GetMapping("/settings/jira")
    public JiraSettingsView jira() {
        return AdminUseCases.jiraSettings(d);
    }

    @PutMapping("/settings/jira")
    public JiraSettingsView saveJira(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String url = b.str("url");
        String email = b.str("email");
        String token = b.nullableStr("token");
        String interval = b.has("interval") ? in.asChoice(b.raw("interval"), b.at("interval"), AdminUseCases.INTERVALS, false)
                : b.str("interval");
        in.check();
        return AdminUseCases.saveJira(d, url, email, token, interval);
    }

    @PostMapping("/settings/jira/test")
    public JiraTestOut testJira(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String url = b.str("url");
        String email = b.str("email", "");
        String token = b.nullableStr("token");
        in.check();
        return new JiraTestOut(AdminUseCases.testJira(d, url, email, token));
    }

    private static String key(byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String key = b.str("key");
        in.check();
        return key;
    }

    @PostMapping("/settings/epics")
    public JiraSettingsView addEpic(@RequestBody(required = false) byte[] raw) {
        return AdminUseCases.addEpic(d, key(raw));
    }

    @DeleteMapping("/settings/epics/{key}")
    public JiraSettingsView removeEpic(@PathVariable String key) {
        return AdminUseCases.removeEpic(d, key);
    }

    // --- field mapping ----------------------------------------------------------------------------------------------

    private static final List<String> TARGETS = List.of("planned_start", "planned_end", "effort");

    @GetMapping("/settings/field-mapping")
    public FieldMappingView fieldMapping() {
        return AdminUseCases.fieldMapping(d);
    }

    @PutMapping("/settings/field-mapping")
    public FieldMappingView saveFieldMapping(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String t : TARGETS) {
            mapping.put(t, b.str(t, ""));
        }
        in.check();
        return AdminUseCases.saveFieldMapping(d, mapping);
    }

    @PostMapping("/settings/field-mapping/check")
    public FieldMappingCheck checkFieldMapping(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String sample = b.str("sample");
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String t : TARGETS) {
            String v = b.nullableStr(t);
            if (v != null) {
                mapping.put(t, v);
            }
        }
        in.check();
        return AdminUseCases.checkFieldMapping(d, sample, mapping);
    }

    // --- status mapping ---------------------------------------------------------------------------------------------

    @GetMapping("/settings/status-mapping")
    public List<StatusRow> statusMapping() {
        return AdminUseCases.statusMapping(d);
    }

    @PutMapping("/settings/status-mapping")
    public List<StatusRow> saveStatusMapping(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        Map<String, String> mapping = b.dict("mapping", (at, v) -> in.asChoice(v, at, STATUS_CHOICES, true));
        in.check();
        return AdminUseCases.saveStatusMapping(d, mapping);
    }

    // --- name rules -------------------------------------------------------------------------------------------------

    @GetMapping("/settings/name-rules")
    public NameRulesView nameRules() {
        return AdminUseCases.nameRules(d);
    }

    @PutMapping("/settings/name-rules")
    public NameRulesView saveNameRules(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String rule = b.str("rule");
        Map<String, String> aliases = b.dict("aliases", (at, v) -> in.asString(v, at, false));
        in.check();
        return AdminUseCases.saveNameRules(d, rule, aliases);
    }

    @PostMapping("/settings/name-rules/preview")
    public NameRulePreview previewNameRule(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String rule = b.str("rule");
        Map<String, String> aliases = b.nullableDict("aliases", (at, v) -> in.asString(v, at, false));
        String sample = b.str("sample", "");
        in.check();
        return AdminUseCases.previewNameRule(d, rule, aliases, sample);
    }

    // --- members ----------------------------------------------------------------------------------------------------

    @PatchMapping("/members/{memberId}")
    public Member updateMember(@PathVariable String memberId, @RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String name = b.nullableStr("name");
        String role = b.has("role") ? in.asChoice(b.raw("role"), b.at("role"), List.of("BA", "BAL", "JM"), true) : null;
        Boolean clearRole = b.bool("clear_role", false);
        String permission = b.has("permission")
                ? in.asChoice(b.raw("permission"), b.at("permission"), List.of("admin", "user"), true) : null;
        String country = b.has("country") ? in.asChoice(b.raw("country"), b.at("country"), COUNTRY_CHOICES, true) : null;
        in.check();
        return AdminUseCases.updateMember(d, memberId, new MemberPatch(name, role != null ? Role.of(role) : null,
                permission != null ? Permission.of(permission) : null, country, clearRole));
    }

    // --- calendar settings ------------------------------------------------------------------------------------------

    @GetMapping("/holidays")
    public List<Holiday> holidays() {
        return AdminUseCases.holidays(d);
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Holiday> addHoliday(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String date = in.pattern(b.at("date"), b.str("date"), Input.ISO_DATE);
        String name = b.str("name");
        in.minLength(b.at("name"), name, 1);
        List<String> countries = b.list("countries", (at, v) -> in.asChoice(v, at, COUNTRY_CHOICES, false));
        in.minItems(b.at("countries"), countries, 1);
        in.check();
        return AdminUseCases.addHoliday(d, date, name, countries);
    }

    @DeleteMapping("/holidays/{date}")
    public List<Holiday> deleteHoliday(@PathVariable String date) {
        return AdminUseCases.deleteHoliday(d, date);
    }

    @GetMapping("/extra-days")
    public Map<String, String> extraDays() {
        return AdminUseCases.extraDays(d);
    }

    @PostMapping("/extra-days")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> addExtraDay(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        String date = in.pattern(b.at("date"), b.str("date"), Input.ISO_DATE);
        String note = b.str("note", "");
        in.check();
        return AdminUseCases.addExtraDay(d, date, note);
    }

    @DeleteMapping("/extra-days/{date}")
    public Map<String, String> deleteExtraDay(@PathVariable String date) {
        return AdminUseCases.deleteExtraDay(d, date);
    }

    @GetMapping("/settings/working-hours")
    public WorkingHours workingHours() {
        return AdminUseCases.workingHours(d);
    }

    @PutMapping("/settings/working-hours")
    public WorkingHours saveWorkingHours(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        Double hours = b.number("hours_per_day");
        in.gt(b.at("hours_per_day"), hours, 0);
        in.le(b.at("hours_per_day"), hours, 24);
        List<Integer> days = b.list("days", (at, v) -> in.asInt(v, at));
        in.minItems(b.at("days"), days, 1);
        Double tolerance = b.number("timesheet_tolerance_h");
        in.ge(b.at("timesheet_tolerance_h"), tolerance, 0);
        in.check();
        return AdminUseCases.saveWorkingHours(d, new WorkingHours(hours, days, tolerance));
    }

    @GetMapping("/settings/alerts")
    public Thresholds alerts() {
        return AdminUseCases.alerts(d);
    }

    @PutMapping("/settings/alerts")
    public Thresholds saveAlerts(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = body(in, raw);
        double[] v = new double[5];
        String[] names = {"soon_days", "effort_warn_pct", "held_days", "review_rounds", "capacity_pct"};
        for (int i = 0; i < names.length; i++) {
            Double x = b.number(names[i]);
            in.ge(b.at(names[i]), x, 0);
            v[i] = x == null ? 0 : x;
        }
        in.check();
        return AdminUseCases.saveAlerts(d, new Thresholds(v[0], v[1], v[2], v[3], v[4]));
    }
}
