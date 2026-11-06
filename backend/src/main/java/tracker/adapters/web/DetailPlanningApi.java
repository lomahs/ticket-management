package tracker.adapters.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import tracker.adapters.web.EstimationFiles.EstimationImportRow;
import tracker.adapters.web.TicketsApi.ExpectedUpdateIn;
import tracker.adapters.web.auth.Open;
import tracker.adapters.web.auth.Open.Rule;
import tracker.adapters.web.auth.Requires;
import tracker.application.AppError.Invalid;
import tracker.application.service.DetailPlanImportService;
import tracker.application.service.DetailPlanImportService.ImportLine;
import tracker.application.service.DetailPlanImportService.ImportLineIn;
import tracker.application.service.DetailPlanService;
import tracker.application.service.DetailPlanService.DetailPlanView;
import tracker.application.service.DetailPlanService.DraftIn;
import tracker.application.service.DetailPlanService.NewRowIn;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.DetailPlanService.StepIn;
import tracker.application.service.EstimationService;
import tracker.application.service.EstimationService.EstimationView;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.MasterScheduleView;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.ReplanService;
import tracker.application.service.ReplanService.ReplanIn;
import tracker.application.service.ReplanService.ReplanView;
import tracker.application.service.ScopeService;
import tracker.application.service.TicketService.ExpectedChange;
import tracker.application.service.TicketService.ExpectedUpdate;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Viewer;

/** Building an anken's plan: estimation, master schedule, detail plan and re-plan. Reads: Planning; writes: admins. */
@RestController
@RequestMapping("/api/v1/planning")
public class DetailPlanningApi {
    private final EstimationService estimation;
    private final MasterScheduleService schedule;
    private final DetailPlanService detail;
    private final DetailPlanImportService detailImport;
    private final ReplanService replan;
    private final ScopeService scopes;

    public DetailPlanningApi(EstimationService estimation, MasterScheduleService schedule, DetailPlanService detail,
                             DetailPlanImportService detailImport, ReplanService replan, ScopeService scopes) {
        this.estimation = estimation;
        this.schedule = schedule;
        this.detail = detail;
        this.detailImport = detailImport;
        this.replan = replan;
        this.scopes = scopes;
    }

    private AnkenScope epicScope(Viewer viewer, String epic) {
        scopes.requireVisible(viewer, epic);
        return new AnkenScope(Set.of(epic));
    }

    /** @param id 0 for a new row; the phase and subphase come from the Task */
    public record EstimationRowIn(long id, @NotNull String functionName, @Nullable Integer steps, double effortH, @Nullable String baId,
                                  @Nullable String balId, @Nullable String jmId, @Nullable String taskKey) {
        EstimationRow row(String epic, int position) {
            return new EstimationRow(id, epic, position, "", "", functionName, steps, effortH, baId, balId, jmId, taskKey, null);
        }
    }

    public record EstimationIn(@NotNull List<@NotNull @Valid EstimationRowIn> rows) {
    }

    @Requires(feature = Feature.PLANNING, level = Level.READ)
    @GetMapping("/{epic}/estimation")
    public EstimationView estimation(@PathVariable("epic") String epic, Viewer viewer) {
        return estimation.view(epicScope(viewer, epic), epic);
    }

    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/estimation")
    public EstimationView saveEstimation(@PathVariable("epic") String epic, @RequestBody @Valid EstimationIn body, Viewer viewer) {
        List<EstimationRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < body.rows().size(); i++) {
            rows.add(body.rows().get(i).row(epic, i));
        }
        return estimation.save(epicScope(viewer, epic), epic, rows);
    }

    /** The multipart body of an estimation import. */
    public record EstimationUpload(@Nullable @Schema(type = "string", format = "binary") MultipartFile file) {
    }

    /** Reads a filled template (.xlsx or .csv) and checks every row, its Task against the anken's; nothing is saved. */
    @Open(Rule.ADMIN)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(implementation = EstimationUpload.class), encoding = @Encoding(name = "file")))
    @PostMapping(value = "/{epic}/estimation/import", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
    public List<EstimationImportRow> importEstimation(@PathVariable("epic") String epic,
                                            @RequestPart(name = "file", required = false) @Nullable MultipartFile file, Viewer viewer)
            throws IOException {
        AnkenScope scope = epicScope(viewer, epic);
        if (file == null) {
            throw new Invalid("Choose a .xlsx or .csv file", "file");
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isEmpty() ? "upload" : file.getOriginalFilename();
        Set<String> tasks = estimation.view(scope, epic).tasks().stream().map(EstimationService.TaskOption::key).collect(Collectors.toSet());
        return EstimationFiles.parse(name, file.getBytes(), tasks);
    }

    @Requires(feature = Feature.PLANNING, level = Level.READ)
    @GetMapping("/estimation/template")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> estimationTemplate() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"estimation-template.csv\"")
                .body(EstimationFiles.TEMPLATE_CSV.getBytes(StandardCharsets.UTF_8));
    }

    /** @param start null: the working day after the previous phase's End (not for the first phase) */
    public record PhaseIn(@NotBlank String phase, @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                          @NotNull List<@NotBlank String> memberIds) {
        PhaseRowIn row() {
            return new PhaseRowIn(phase, start, memberIds);
        }
    }

    public record SchedulePreviewIn(@NotNull List<@NotNull @Valid PhaseIn> phases) {
    }

    public record ScheduleIn(@NotNull List<@NotNull @Valid PhaseIn> phases, @NotNull String reason) {
    }

    @Requires(feature = Feature.PLANNING, level = Level.READ)
    @GetMapping("/{epic}/master-schedule")
    public MasterScheduleView masterSchedule(@PathVariable("epic") String epic, Viewer viewer) {
        return schedule.view(epicScope(viewer, epic), epic);
    }

    /** The phases as they would be saved: effort, end and plan MD; nothing is stored. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/master-schedule/preview")
    public List<PhaseRow> previewMasterSchedule(@PathVariable("epic") String epic, @RequestBody @Valid SchedulePreviewIn body, Viewer viewer) {
        return schedule.preview(epicScope(viewer, epic), epic, body.phases().stream().map(PhaseIn::row).toList());
    }

    /** Saves a new version of the master schedule; the first one is the baseline. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/master-schedule")
    public MasterScheduleView saveMasterSchedule(@PathVariable("epic") String epic, @RequestBody @Valid ScheduleIn body, Viewer viewer) {
        return schedule.save(viewer, epicScope(viewer, epic), epic, body.phases().stream().map(PhaseIn::row).toList(), body.reason());
    }

    /** @param order the unpushed rows' ids in their new order */
    public record DraftBody(@NotNull List<@NotNull Long> order, @NotNull List<@NotNull RowAssign> assigns) {
        DraftIn draft() {
            return new DraftIn(order, assigns);
        }
    }

    @Requires(feature = Feature.PLANNING, level = Level.READ)
    @GetMapping("/{epic}/detail")
    public DetailPlanView detailPlan(@PathVariable("epic") String epic, Viewer viewer) {
        return detail.view(epicScope(viewer, epic), epic);
    }

    /** Saves the draft (row order and people) and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/detail")
    public DetailPlanView saveDetailPlan(@PathVariable("epic") String epic, @RequestBody @Valid DraftBody body, Viewer viewer) {
        return detail.saveDraft(epicScope(viewer, epic), epic, body.draft());
    }

    /** A change to one sub-task of the plan; {@code start} and {@code end} together (dates set by hand) or neither. */
    public record StepBody(long rowId, @Nullable String step, @Nullable @PositiveOrZero Double effortH,
                           @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                           @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String end, boolean removed) {
        StepIn in() {
            return new StepIn(rowId, step, effortH, start, end, removed);
        }
    }

    /** Changes the effort, the dates or the removal of one sub-task and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/detail/step")
    public DetailPlanView editDetailStep(@PathVariable("epic") String epic, @RequestBody @Valid StepBody body, Viewer viewer) {
        return detail.editStep(epicScope(viewer, epic), epic, body.in());
    }

    /** @param steps 3 or 5 for a function; null for a ticket */
    public record NewRowBody(@NotBlank String taskKey, @NotBlank String functionName, @Nullable Integer steps, @Positive double effortH) {
    }

    /** Adds a function or a ticket to the estimation from the plan and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/rows")
    public DetailPlanView addDetailRow(@PathVariable("epic") String epic, @RequestBody @Valid NewRowBody body, Viewer viewer) {
        return detail.addRow(epicScope(viewer, epic), epic, new NewRowIn(body.taskKey(), body.functionName(), body.steps(), body.effortH()));
    }

    /** Fills the empty BA / BAL / JM slots (docs/auto-allocation.md), plans every row again and saves the plan; returns it. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/auto-allocate")
    public DetailPlanView autoAllocate(@PathVariable("epic") String epic, Viewer viewer) {
        return detail.autoAllocate(epicScope(viewer, epic), epic);
    }

    /** The plan as it stands (effort per day, overruns, phase checks), without planning again; nothing is saved. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/check")
    public DetailPlanView checkDetailPlan(@PathVariable("epic") String epic, Viewer viewer) {
        return detail.check(epicScope(viewer, epic), epic);
    }

    /**
     * Empties the BA / BAL / JM of the unpushed rows and returns the plan. {@code keep_input}: only Auto allocate's picks; else
     * every pick and every date, effort and removal set by hand.
     */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/clear")
    public DetailPlanView clearDetailPlan(@PathVariable("epic") String epic,
                                          @RequestParam(name = "keep_input", defaultValue = "false") boolean keepInput, Viewer viewer) {
        return detail.clear(epicScope(viewer, epic), epic, keepInput);
    }

    /** The plan's sub-tasks not in Jira yet as an .xlsx to change offline and import back, with the anken's members. */
    @Requires(feature = Feature.PLANNING, level = Level.READ)
    @GetMapping("/{epic}/detail/template")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = DetailPlanFiles.XLSX, schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> detailTemplate(@PathVariable("epic") String epic, Viewer viewer) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(DetailPlanFiles.XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"detail-plan-" + epic + ".xlsx\"")
                .body(DetailPlanFiles.template(detailImport.sheet(epicScope(viewer, epic), epic)));
    }

    /** The multipart body of a detail plan import. */
    public record DetailPlanUpload(@Nullable @Schema(type = "string", format = "binary") MultipartFile file) {
    }

    /** Reads a filled template (.xlsx or .csv) and checks every line against the plan; nothing is saved. */
    @Open(Rule.ADMIN)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(implementation = DetailPlanUpload.class), encoding = @Encoding(name = "file")))
    @PostMapping(value = "/{epic}/detail/import/preview", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
    public List<ImportLine> previewDetailImport(@PathVariable("epic") String epic,
                                                @RequestPart(name = "file", required = false) @Nullable MultipartFile file, Viewer viewer)
            throws IOException {
        AnkenScope scope = epicScope(viewer, epic);
        if (file == null) {
            throw new Invalid("Choose a .xlsx or .csv file", "file");
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isEmpty() ? "upload" : file.getOriginalFilename();
        return detailImport.preview(scope, epic, LeaveFiles.readRows(name, file.getBytes()));
    }

    /** A line of the preview to apply; null keeps what the plan has. */
    public record ImportLineBody(@NotBlank String ref, @Nullable String baId, @Nullable String balId, @Nullable String jmId,
                                 @Nullable @PositiveOrZero Double effortH,
                                 @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                                 @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String end) {
        ImportLineIn in() {
            return new ImportLineIn(ref, baId, balId, jmId, effortH, start, end);
        }
    }

    public record DetailImportBody(@NotNull @Size(max = 2000) List<@NotNull @Valid ImportLineBody> lines) {
    }

    /** Applies the lines of a preview (people picked, efforts and dates set by hand) and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/import")
    public DetailPlanView applyDetailImport(@PathVariable("epic") String epic, @RequestBody @Valid DetailImportBody body, Viewer viewer) {
        return detailImport.apply(epicScope(viewer, epic), epic, body.lines().stream().map(ImportLineBody::in).toList());
    }

    /**
     * The review of the push: every sub-task, with the requests, and the dates the plan shows; refused while the plan has input
     * problems (over a budget or over the day's allocation only warn).
     */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/push/preview")
    public BatchPreview previewDetailPush(@PathVariable("epic") String epic, Viewer viewer) {
        return detail.pushPreview(epicScope(viewer, epic), epic);
    }

    /** Creates the plan's sub-tasks in Jira; rows whose sub-tasks were all created are marked pushed. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/push")
    public BatchResult pushDetailPlan(@PathVariable("epic") String epic, Viewer viewer) {
        return detail.push(viewer, epicScope(viewer, epic), epic);
    }

    /** @param assignees sub-task key → the member it moves to */
    public record ReplanBody(@NotBlank String phase, @NotNull Map<String, @NotBlank String> assignees) {
        ReplanIn in() {
            return new ReplanIn(phase, assignees);
        }
    }

    /** The review's changes, sent back unchanged with the same phase and assignees. */
    public record ReplanApplyIn(@NotBlank String phase, @NotNull Map<String, @NotBlank String> assignees,
                                @NotNull @Size(max = 300) List<@NotNull @Valid ExpectedUpdateIn> updates) {
    }

    /** Every sub-task of the phase except the Closed ones, with the dates and assignee they would get. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/replan")
    public ReplanView replan(@PathVariable("epic") String epic, @RequestBody @Valid ReplanBody body, Viewer viewer) {
        return replan.compute(epicScope(viewer, epic), epic, body.in());
    }

    /** Reads the moved sub-tasks from Jira and shows what the re-plan changes; refused on problems or overruns. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/replan/preview")
    public BatchPreview previewReplan(@PathVariable("epic") String epic, @RequestBody @Valid ReplanBody body, Viewer viewer) {
        return replan.preview(epicScope(viewer, epic), epic, body.in());
    }

    /** Applies a reviewed re-plan; 409 when Jira changed since the review, 422 when the re-plan did. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/replan/apply")
    public BatchResult applyReplan(@PathVariable("epic") String epic, @RequestBody @Valid ReplanApplyIn body, Viewer viewer) {
        return replan.apply(viewer, epicScope(viewer, epic), epic, new ReplanIn(body.phase(), body.assignees()), body.updates().stream()
                .map(u -> new ExpectedUpdate(u.key(), u.changes().stream().map(c -> new ExpectedChange(c.field(), c.before(), c.after())).toList()))
                .toList());
    }
}
