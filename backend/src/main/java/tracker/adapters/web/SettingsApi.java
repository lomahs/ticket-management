package tracker.adapters.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Schema;

import tracker.adapters.web.auth.Open.Rule;
import tracker.adapters.web.auth.Open;
import tracker.application.service.JiraSettingsService.AnkenSettingsView;
import tracker.application.service.JiraSettingsService.FieldMappingCheck;
import tracker.application.service.JiraSettingsService.FieldMappingView;
import tracker.application.service.JiraSettingsService.JiraSettingsView;
import tracker.application.service.JiraSettingsService.NameRulePreview;
import tracker.application.service.JiraSettingsService.NameRulesView;
import tracker.application.service.JiraSettingsService.StatusRow;
import tracker.application.service.JiraSettingsService.StepWeightsView;
import tracker.application.service.JiraSettingsService.StructureView;
import tracker.application.service.JiraSettingsService;
import tracker.application.service.TeamSettingsService;
import tracker.domain.Holiday;
import tracker.domain.Status;
import tracker.domain.TaskTemplate;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;
import tracker.domain.WorkStructure;

@RestController
@RequestMapping("/api/v1")
public class SettingsApi {
    private final JiraSettingsService jiraSettings;
    private final TeamSettingsService teamSettings;

    public SettingsApi(JiraSettingsService jiraSettings, TeamSettingsService teamSettings) {
        this.jiraSettings = jiraSettings;
        this.teamSettings = teamSettings;
    }

    public record JiraTestOut(@Schema(allowableValues = {"ok", "fail"}) String status) {
    }

    // --- Jira connection & epics ------------------------------------------------------------------------------------

    /** @param token a new API token; left out (or null) keeps the stored one */
    public record JiraIn(@NotNull String url, @NotNull String email, @Nullable String token,
                         @NotNull @Schema(allowableValues = {"5", "15", "60", "manual"}) String interval) {
    }

    /** @param token null tests with the stored token */
    public record JiraTestIn(@NotNull String url, @Nullable String email, @Nullable String token) {
    }

    public record EpicIn(@NotNull String key) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/jira")
    public JiraSettingsView jira() {
        return jiraSettings.jiraSettings();
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/jira")
    public JiraSettingsView saveJira(@RequestBody @Valid JiraIn body) {
        return jiraSettings.saveJira(body.url(), body.email(), body.token(), body.interval());
    }

    /** @param time "HH:mm" in the team's time zone, or "off" */
    public record DailyCaptureIn(@NotNull @Pattern(regexp = "off|([01]\\d|2[0-3]):[0-5]\\d", message = "must be HH:mm or off") String time) {
    }

    public record DailyCaptureView(String time) {
    }

    /** When the Daily Overview of every tracked anken is captured on working days. */
    @Open(Rule.ADMIN)
    @GetMapping("/settings/daily-capture")
    public DailyCaptureView dailyCapture() {
        return new DailyCaptureView(teamSettings.dailyCapture());
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/daily-capture")
    public DailyCaptureView saveDailyCapture(@RequestBody @Valid DailyCaptureIn body) {
        return new DailyCaptureView(teamSettings.saveDailyCapture(body.time()));
    }

    @Open(Rule.ADMIN)
    @PostMapping("/settings/jira/test")
    public JiraTestOut testJira(@RequestBody @Valid JiraTestIn body) {
        return new JiraTestOut(jiraSettings.testJira(body.url(), body.email() == null ? "" : body.email(), body.token()));
    }

    @Open(Rule.ADMIN)
    @PostMapping("/settings/epics")
    public JiraSettingsView addEpic(@RequestBody @Valid EpicIn body) {
        return jiraSettings.addEpic(body.key());
    }

    @Open(Rule.ADMIN)
    @DeleteMapping("/settings/epics/{key}")
    public JiraSettingsView removeEpic(@PathVariable("key") String key) {
        return jiraSettings.removeEpic(key);
    }

    // --- field mapping ----------------------------------------------------------------------------------------------

    /** Jira field ids ("customfield_10015"); empty for none. */
    public record FieldMappingIn(@Nullable String plannedStart, @Nullable String plannedEnd, @Nullable String effort,
                                 @Nullable String remaining) {
    }

    /** Reads a sample issue with the given field ids (null: the saved ones). */
    public record FieldCheckIn(@NotNull String sample, @Nullable String plannedStart, @Nullable String plannedEnd,
                               @Nullable String effort, @Nullable String remaining) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/field-mapping")
    public FieldMappingView fieldMapping() {
        return jiraSettings.fieldMapping();
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/field-mapping")
    public FieldMappingView saveFieldMapping(@RequestBody @Valid FieldMappingIn body) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("planned_start", body.plannedStart() == null ? "" : body.plannedStart());
        mapping.put("planned_end", body.plannedEnd() == null ? "" : body.plannedEnd());
        mapping.put("effort", body.effort() == null ? "" : body.effort());
        mapping.put("remaining", body.remaining() == null ? "" : body.remaining());
        return jiraSettings.saveFieldMapping(mapping);
    }

    @Open(Rule.ADMIN)
    @PostMapping("/settings/field-mapping/check")
    public FieldMappingCheck checkFieldMapping(@RequestBody @Valid FieldCheckIn body) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (body.plannedStart() != null) {
            mapping.put("planned_start", body.plannedStart());
        }
        if (body.plannedEnd() != null) {
            mapping.put("planned_end", body.plannedEnd());
        }
        if (body.effort() != null) {
            mapping.put("effort", body.effort());
        }
        if (body.remaining() != null) {
            mapping.put("remaining", body.remaining());
        }
        return jiraSettings.checkFieldMapping(body.sample(), mapping);
    }

    // --- status mapping ---------------------------------------------------------------------------------------------

    /** @param mapping Jira status name → tracker status; null leaves the status unmapped */
    public record StatusMappingIn(@NotNull Map<String, @Nullable Status> mapping) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/status-mapping")
    public List<StatusRow> statusMapping() {
        return jiraSettings.statusMapping();
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/status-mapping")
    public List<StatusRow> saveStatusMapping(@RequestBody @Valid StatusMappingIn body) {
        return jiraSettings.saveStatusMapping(body.mapping());
    }

    // --- phases & tasks ---------------------------------------------------------------------------------------------

    /**
     * @param match    the Jira Task Summary to recognise; case-insensitive, {@code *} matches any text
     * @param prefix   the value of {prefix} in this Task's sub-task names; default ""
     * @param workflow whether the sub-tasks follow the 5-step review workflow; default true
     * @param type     qna | bug | work_request: what the sub-tasks are on the Daily Overview; default "" (none)
     */
    public record TaskTemplateIn(@NotNull String match, @NotNull String phase, @Nullable String prefix, @Nullable Boolean workflow,
                                 @Nullable @Pattern(regexp = "|qna|bug|work_request", message = "must be qna, bug or work_request")
                                 @Schema(allowableValues = {"", "qna", "bug", "work_request"}) String type) {
        TaskTemplate domain() {
            return new TaskTemplate(match, phase, prefix == null ? "" : prefix, workflow == null || workflow, type == null ? "" : type);
        }
    }

    /** @param phases in project order */
    public record StructureIn(@NotNull List<@NotNull String> phases, @Nullable List<@NotNull @Valid TaskTemplateIn> tasks) {
        WorkStructure domain() {
            return new WorkStructure(phases, tasks == null ? List.of() : tasks.stream().map(TaskTemplateIn::domain).toList());
        }
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/structure")
    public StructureView structure(@RequestParam(required = false) @Nullable String anken) {
        return jiraSettings.structure(anken);
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/structure")
    public StructureView saveStructure(@RequestParam(required = false) @Nullable String anken, @RequestBody @Valid StructureIn body) {
        return jiraSettings.saveStructure(anken, body.domain());
    }

    @Open(Rule.ADMIN)
    @PostMapping("/settings/structure/preview")
    public StructureView previewStructure(@RequestParam(required = false) @Nullable String anken, @RequestBody @Valid StructureIn body) {
        return jiraSettings.previewStructure(anken, body.domain());
    }

    // --- name rules -------------------------------------------------------------------------------------------------

    /** @param aliases step key ("create", "balr", …) → comma-separated names */
    public record NameRulesIn(@NotNull String rule, @NotNull Map<String, @NotNull String> aliases) {
    }

    /** @param aliases null uses the saved aliases */
    public record NameRulePreviewIn(@NotNull String rule, @Nullable Map<String, @NotNull String> aliases, @Nullable String sample) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/name-rules")
    public NameRulesView nameRules(@RequestParam(required = false) @Nullable String anken) {
        return jiraSettings.nameRules(anken);
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/name-rules")
    public NameRulesView saveNameRules(@RequestParam(required = false) @Nullable String anken, @RequestBody @Valid NameRulesIn body) {
        return jiraSettings.saveNameRules(anken, body.rule(), body.aliases());
    }

    @Open(Rule.ADMIN)
    @PostMapping("/settings/name-rules/preview")
    public NameRulePreview previewNameRule(@RequestParam(required = false) @Nullable String anken, @RequestBody @Valid NameRulePreviewIn body) {
        return jiraSettings.previewNameRule(anken, body.rule(), body.aliases(), body.sample() == null ? "" : body.sample());
    }

    /** @param weights step key ("create", "balr", "fixb", "jmr", "fixj") → weight */
    public record StepWeightsIn(@NotNull Map<String, @NotNull Double> weights) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/step-weights")
    public StepWeightsView stepWeights(@RequestParam(required = false) @Nullable String anken) {
        return jiraSettings.stepWeights(anken);
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/step-weights")
    public StepWeightsView saveStepWeights(@RequestParam(required = false) @Nullable String anken, @RequestBody @Valid StepWeightsIn body) {
        return jiraSettings.saveStepWeights(anken, body.weights());
    }

    @Open(Rule.ADMIN)
    @DeleteMapping("/settings/step-weights")
    public StepWeightsView resetStepWeights(@RequestParam String anken) {
        return jiraSettings.resetStepWeights(anken);
    }

    public record CopyIn(@NotBlank String from) {
    }

    /** Copies the Phases & Tasks and name rules of "default" or another anken onto this one, then runs a full sync. */
    @Open(Rule.ADMIN)
    @PostMapping("/settings/anken/{epic}/copy")
    public AnkenSettingsView copySettings(@PathVariable String epic, @RequestBody @Valid CopyIn body) {
        return jiraSettings.copySettings(epic, body.from());
    }

    // --- calendar settings ------------------------------------------------------------------------------------------

    public record HolidayIn(@NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String date,
                            @NotNull @Size(min = 1) String name, @NotNull @Size(min = 1) List<@NotNull Country> countries) {
    }

    public record ExtraDayIn(@NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String date,
                             @Nullable String note) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/holidays")
    public List<Holiday> holidays() {
        return teamSettings.holidays();
    }

    @Open(Rule.ADMIN)
    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Holiday> addHoliday(@RequestBody @Valid HolidayIn body) {
        return teamSettings.addHoliday(body.date(), body.name(), body.countries().stream().map(Country::name).toList());
    }

    @Open(Rule.ADMIN)
    @DeleteMapping("/holidays/{date}")
    public List<Holiday> deleteHoliday(@PathVariable("date") String date) {
        return teamSettings.deleteHoliday(date);
    }

    @Open(Rule.ADMIN)
    @GetMapping("/extra-days")
    public Map<String, String> extraDays() {
        return teamSettings.extraDays();
    }

    @Open(Rule.ADMIN)
    @PostMapping("/extra-days")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> addExtraDay(@RequestBody @Valid ExtraDayIn body) {
        return teamSettings.addExtraDay(body.date(), body.note() == null ? "" : body.note());
    }

    @Open(Rule.ADMIN)
    @DeleteMapping("/extra-days/{date}")
    public Map<String, String> deleteExtraDay(@PathVariable("date") String date) {
        return teamSettings.deleteExtraDay(date);
    }

    /** @param days working weekdays, 1 = Monday … 7 = Sunday */
    public record WorkingHoursIn(@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("24") Double hoursPerDay,
                                 @NotNull @Size(min = 1) List<@NotNull Integer> days,
                                 @NotNull @DecimalMin("0") Double timesheetToleranceH) {
    }

    public record AlertsIn(@NotNull @DecimalMin("0") Double soonDays, @NotNull @DecimalMin("0") Double effortWarnPct,
                           @NotNull @DecimalMin("0") Double heldDays, @NotNull @DecimalMin("0") Double reviewRounds,
                           @NotNull @DecimalMin("0") Double capacityPct,
                           @NotNull @DecimalMin("0") Double effortDiffH) {
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/working-hours")
    public WorkingHours workingHours() {
        return teamSettings.workingHours();
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/working-hours")
    public WorkingHours saveWorkingHours(@RequestBody @Valid WorkingHoursIn body) {
        return teamSettings.saveWorkingHours(new WorkingHours(body.hoursPerDay(), body.days(), body.timesheetToleranceH()));
    }

    @Open(Rule.ADMIN)
    @GetMapping("/settings/alerts")
    public Thresholds alerts() {
        return teamSettings.alerts();
    }

    @Open(Rule.ADMIN)
    @PutMapping("/settings/alerts")
    public Thresholds saveAlerts(@RequestBody @Valid AlertsIn body) {
        return teamSettings.saveAlerts(new Thresholds(body.soonDays(), body.effortWarnPct(), body.heldDays(), body.reviewRounds(),
                body.capacityPct(), body.effortDiffH()));
    }
}
