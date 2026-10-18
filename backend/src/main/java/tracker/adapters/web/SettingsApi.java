package tracker.adapters.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
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

import io.swagger.v3.oas.annotations.media.Schema;

import tracker.application.service.JiraSettingsService.FieldMappingCheck;
import tracker.application.service.JiraSettingsService.FieldMappingView;
import tracker.application.service.JiraSettingsService.JiraSettingsView;
import tracker.application.service.JiraSettingsService.NameRulePreview;
import tracker.application.service.JiraSettingsService.NameRulesView;
import tracker.application.service.JiraSettingsService.StatusRow;
import tracker.application.service.JiraSettingsService.StructureView;
import tracker.application.service.JiraSettingsService;
import tracker.application.service.TeamSettingsService.MemberPatch;
import tracker.application.service.TeamSettingsService;
import tracker.domain.Holiday;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;
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

    @GetMapping("/settings/jira")
    public JiraSettingsView jira() {
        return jiraSettings.jiraSettings();
    }

    @PutMapping("/settings/jira")
    public JiraSettingsView saveJira(@RequestBody @Valid JiraIn body) {
        return jiraSettings.saveJira(body.url(), body.email(), body.token(), body.interval());
    }

    @PostMapping("/settings/jira/test")
    public JiraTestOut testJira(@RequestBody @Valid JiraTestIn body) {
        return new JiraTestOut(jiraSettings.testJira(body.url(), body.email() == null ? "" : body.email(), body.token()));
    }

    @PostMapping("/settings/epics")
    public JiraSettingsView addEpic(@RequestBody @Valid EpicIn body) {
        return jiraSettings.addEpic(body.key());
    }

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

    @GetMapping("/settings/field-mapping")
    public FieldMappingView fieldMapping() {
        return jiraSettings.fieldMapping();
    }

    @PutMapping("/settings/field-mapping")
    public FieldMappingView saveFieldMapping(@RequestBody @Valid FieldMappingIn body) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("planned_start", body.plannedStart() == null ? "" : body.plannedStart());
        mapping.put("planned_end", body.plannedEnd() == null ? "" : body.plannedEnd());
        mapping.put("effort", body.effort() == null ? "" : body.effort());
        mapping.put("remaining", body.remaining() == null ? "" : body.remaining());
        return jiraSettings.saveFieldMapping(mapping);
    }

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

    @GetMapping("/settings/status-mapping")
    public List<StatusRow> statusMapping() {
        return jiraSettings.statusMapping();
    }

    @PutMapping("/settings/status-mapping")
    public List<StatusRow> saveStatusMapping(@RequestBody @Valid StatusMappingIn body) {
        return jiraSettings.saveStatusMapping(body.mapping());
    }

    // --- phases & tasks ---------------------------------------------------------------------------------------------

    /**
     * @param match    the Jira Task Summary to recognise; case-insensitive, {@code *} matches any text
     * @param prefix   the value of {prefix} in this Task's sub-task names; default ""
     * @param workflow whether the sub-tasks follow the 5-step review workflow; default true
     */
    public record TaskTemplateIn(@NotNull String match, @NotNull String phase, @Nullable String prefix, @Nullable Boolean workflow) {
        TaskTemplate domain() {
            return new TaskTemplate(match, phase, prefix == null ? "" : prefix, workflow == null || workflow);
        }
    }

    /** @param phases in project order */
    public record StructureIn(@NotNull List<@NotNull String> phases, @Nullable List<@NotNull @Valid TaskTemplateIn> tasks) {
        WorkStructure domain() {
            return new WorkStructure(phases, tasks == null ? List.of() : tasks.stream().map(TaskTemplateIn::domain).toList());
        }
    }

    @GetMapping("/settings/structure")
    public StructureView structure() {
        return jiraSettings.structure();
    }

    @PutMapping("/settings/structure")
    public StructureView saveStructure(@RequestBody @Valid StructureIn body) {
        return jiraSettings.saveStructure(body.domain());
    }

    @PostMapping("/settings/structure/preview")
    public StructureView previewStructure(@RequestBody @Valid StructureIn body) {
        return jiraSettings.previewStructure(body.domain());
    }

    // --- name rules -------------------------------------------------------------------------------------------------

    /** @param aliases step key ("create", "balr", …) → comma-separated names */
    public record NameRulesIn(@NotNull String rule, @NotNull Map<String, @NotNull String> aliases) {
    }

    /** @param aliases null uses the saved aliases */
    public record NameRulePreviewIn(@NotNull String rule, @Nullable Map<String, @NotNull String> aliases, @Nullable String sample) {
    }

    @GetMapping("/settings/name-rules")
    public NameRulesView nameRules() {
        return jiraSettings.nameRules();
    }

    @PutMapping("/settings/name-rules")
    public NameRulesView saveNameRules(@RequestBody @Valid NameRulesIn body) {
        return jiraSettings.saveNameRules(body.rule(), body.aliases());
    }

    @PostMapping("/settings/name-rules/preview")
    public NameRulePreview previewNameRule(@RequestBody @Valid NameRulePreviewIn body) {
        return jiraSettings.previewNameRule(body.rule(), body.aliases(), body.sample() == null ? "" : body.sample());
    }

    // --- members ----------------------------------------------------------------------------------------------------

    /** Fields left out (or null) are not changed; {@code clear_role} removes the role. */
    public record MemberPatchIn(@Nullable String name, @Nullable Role role, @Nullable Boolean clearRole,
                                @Nullable Permission permission, @Nullable Country country) {
    }

    @PatchMapping("/members/{member_id}")
    public Member updateMember(@PathVariable("member_id") String memberId, @RequestBody @Valid MemberPatchIn body) {
        return teamSettings.updateMember(memberId, new MemberPatch(body.name(), body.role(), body.permission(),
                body.country() != null ? body.country().name() : null, Boolean.TRUE.equals(body.clearRole())));
    }

    // --- calendar settings ------------------------------------------------------------------------------------------

    public record HolidayIn(@NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String date,
                            @NotNull @Size(min = 1) String name, @NotNull @Size(min = 1) List<@NotNull Country> countries) {
    }

    public record ExtraDayIn(@NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String date,
                             @Nullable String note) {
    }

    @GetMapping("/holidays")
    public List<Holiday> holidays() {
        return teamSettings.holidays();
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Holiday> addHoliday(@RequestBody @Valid HolidayIn body) {
        return teamSettings.addHoliday(body.date(), body.name(), body.countries().stream().map(Country::name).toList());
    }

    @DeleteMapping("/holidays/{date}")
    public List<Holiday> deleteHoliday(@PathVariable("date") String date) {
        return teamSettings.deleteHoliday(date);
    }

    @GetMapping("/extra-days")
    public Map<String, String> extraDays() {
        return teamSettings.extraDays();
    }

    @PostMapping("/extra-days")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> addExtraDay(@RequestBody @Valid ExtraDayIn body) {
        return teamSettings.addExtraDay(body.date(), body.note() == null ? "" : body.note());
    }

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
                           @NotNull @DecimalMin("0") Double capacityPct) {
    }

    @GetMapping("/settings/working-hours")
    public WorkingHours workingHours() {
        return teamSettings.workingHours();
    }

    @PutMapping("/settings/working-hours")
    public WorkingHours saveWorkingHours(@RequestBody @Valid WorkingHoursIn body) {
        return teamSettings.saveWorkingHours(new WorkingHours(body.hoursPerDay(), body.days(), body.timesheetToleranceH()));
    }

    @GetMapping("/settings/alerts")
    public Thresholds alerts() {
        return teamSettings.alerts();
    }

    @PutMapping("/settings/alerts")
    public Thresholds saveAlerts(@RequestBody @Valid AlertsIn body) {
        return teamSettings.saveAlerts(new Thresholds(body.soonDays(), body.effortWarnPct(), body.heldDays(), body.reviewRounds(),
                body.capacityPct()));
    }
}
