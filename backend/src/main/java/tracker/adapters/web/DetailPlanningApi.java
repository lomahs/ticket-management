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
import tracker.application.service.DetailPlanService.CellIn;
import tracker.application.service.DetailPlanService.JiraLineIn;
import tracker.application.service.DetailPlanService.PushIn;
import tracker.application.service.DetailPlanService.ShiftIn;
import tracker.application.service.DetailPlanService.StepIn;
import tracker.application.service.EstimationService;
import tracker.application.service.EstimationService.EstimationView;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.MasterScheduleView;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.ScopeService;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Viewer;

/** Building an anken's plan: estimation, master schedule, detail plan. Reads: Planning; writes: admins. */
@RestController
@RequestMapping("/api/v1/planning")
public class DetailPlanningApi {
    private final EstimationService estimation;
    private final MasterScheduleService schedule;
    private final DetailPlanService detail;
    private final DetailPlanImportService detailImport;
    private final ScopeService scopes;

    public DetailPlanningApi(EstimationService estimation, MasterScheduleService schedule, DetailPlanService detail,
                             DetailPlanImportService detailImport, ScopeService scopes) {
        this.estimation = estimation;
        this.schedule = schedule;
        this.detail = detail;
        this.detailImport = detailImport;
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

    /** A change to one sub-task of the plan. */
    public record StepBody(long rowId, @Nullable String step, @Nullable @PositiveOrZero Double effortH, boolean removed) {
        StepIn in() {
            return new StepIn(rowId, step, effortH, removed);
        }
    }

    /** Changes the effort or the removal of one sub-task and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/detail/step")
    public DetailPlanView editDetailStep(@PathVariable("epic") String epic, @RequestBody @Valid StepBody body, Viewer viewer) {
        return detail.editStep(epicScope(viewer, epic), epic, body.in());
    }

    /** Hours typed in a cell; 0 clears it. */
    public record CellBody(@NotBlank String ref, @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String day,
                           @PositiveOrZero double hours) {
    }

    public record CellsBody(@NotNull List<@Valid CellBody> cells) {
    }

    /** Types hours into cells and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/detail/cells")
    public DetailPlanView saveDetailCells(@PathVariable("epic") String epic, @RequestBody @Valid CellsBody body, Viewer viewer) {
        return detail.saveCells(epicScope(viewer, epic), epic,
                body.cells().stream().map(c -> new CellIn(c.ref(), c.day(), c.hours())).toList());
    }

    /** @param days working days to move; negative is earlier */
    public record ShiftBody(@NotNull @Size(min = 1) List<@NotBlank String> refs, int days) {
    }

    /** Moves the hours of lines (the steps of the picked functions) from today on and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/shift")
    public DetailPlanView shiftDetailPlan(@PathVariable("epic") String epic, @RequestBody @Valid ShiftBody body, Viewer viewer) {
        return detail.shift(epicScope(viewer, epic), epic, new ShiftIn(body.refs(), body.days()));
    }

    /** @param totalH Spent + Remaining wanted; null keeps it. @param picId null keeps it */
    public record JiraLineBody(@Nullable @PositiveOrZero Double totalH, @Nullable String picId) {
    }

    /** Changes the Total and the PIC of a sub-task in Jira (kept until the push) and returns the plan that follows. */
    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/detail/jira/{key}")
    public DetailPlanView editDetailJiraLine(@PathVariable("epic") String epic, @PathVariable("key") String key,
                                             @RequestBody @Valid JiraLineBody body, Viewer viewer) {
        return detail.editJiraLine(epicScope(viewer, epic), epic, key, new JiraLineIn(body.totalH(), body.picId()));
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

    /** Fills the empty BA / BAL / JM slots (docs/auto-allocation.md); returns the plan. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/auto-allocate")
    public DetailPlanView autoAllocate(@PathVariable("epic") String epic, Viewer viewer) {
        return detail.autoAllocate(epicScope(viewer, epic), epic);
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
        List<List<String>> all = LeaveFiles.readAllRows(name, file.getBytes());
        return detailImport.preview(scope, epic, all.isEmpty() ? List.of() : all.getFirst(), all.stream().skip(1).toList());
    }

    /** A line of the preview to apply; null keeps what the plan has. */
    public record ImportLineBody(@NotBlank String ref, @Nullable String baId, @Nullable String balId, @Nullable String jmId,
                                 @Nullable @PositiveOrZero Double effortH, @Nullable Map<String, Double> cells) {
        ImportLineIn in() {
            return new ImportLineIn(ref, baId, balId, jmId, effortH, cells);
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
    public BatchPreview previewDetailPush(@PathVariable("epic") String epic, @RequestBody(required = false) @Nullable PushBody body,
                                          Viewer viewer) {
        return detail.pushPreview(epicScope(viewer, epic), epic, in(body));
    }

    /**
     * What to push: the lines picked ({@code refs}; all when null) and whether past Not yet plan, steps out of order and the
     * master schedule ({@code force}); no Task, no PIC or no epic still stop it.
     */
    public record PushBody(@Nullable List<@NotBlank String> refs, boolean force) {
    }

    private static PushIn in(@Nullable PushBody body) {
        return body == null ? PushIn.ALL : new PushIn(body.refs(), body.force());
    }

    /** Creates the plan's sub-tasks in Jira; rows whose sub-tasks were all created are marked pushed. */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/push")
    public BatchResult pushDetailPlan(@PathVariable("epic") String epic, @RequestBody(required = false) @Nullable PushBody body, Viewer viewer) {
        return detail.push(viewer, epicScope(viewer, epic), epic, in(body));
    }

    /**
     * Saves a version of the plan as it shows now and returns the plan. {@code baseline} (Planning → Detail plan): only once the
     * plan is in Jira and up to date with it; the first such version is the anken's Baseline plan.
     */
    @Open(Rule.ADMIN)
    @PostMapping("/{epic}/detail/versions")
    public DetailPlanView saveDetailVersion(@PathVariable("epic") String epic, @RequestParam(name = "baseline", defaultValue = "false") boolean baseline,
                                            Viewer viewer) {
        return detail.saveVersion(viewer, epicScope(viewer, epic), epic, baseline);
    }
}
