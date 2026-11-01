package tracker.adapters.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
import tracker.application.service.EstimationService;
import tracker.application.service.EstimationService.EstimationView;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.MasterScheduleView;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.ScopeService;
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
    private final ScopeService scopes;

    public DetailPlanningApi(EstimationService estimation, MasterScheduleService schedule, ScopeService scopes) {
        this.estimation = estimation;
        this.schedule = schedule;
        this.scopes = scopes;
    }

    private AnkenScope epicScope(Viewer viewer, String epic) {
        scopes.requireVisible(viewer, epic);
        return new AnkenScope(Set.of(epic));
    }

    public record EstimationIn(@NotNull List<@NotNull @Valid EstimationRow> rows) {
    }

    @Requires(feature = Feature.PLANNING, level = Level.READ)
    @GetMapping("/{epic}/estimation")
    public EstimationView estimation(@PathVariable("epic") String epic, Viewer viewer) {
        return estimation.view(epicScope(viewer, epic), epic);
    }

    @Open(Rule.ADMIN)
    @PutMapping("/{epic}/estimation")
    public EstimationView saveEstimation(@PathVariable("epic") String epic, @RequestBody @Valid EstimationIn body, Viewer viewer) {
        return estimation.save(epicScope(viewer, epic), epic, body.rows());
    }

    /** The multipart body of an estimation import. */
    public record EstimationUpload(@Nullable @Schema(type = "string", format = "binary") MultipartFile file) {
    }

    /** Reads a filled template (.xlsx or .csv) and checks every row; nothing is saved. */
    @Open(Rule.ADMIN)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(implementation = EstimationUpload.class), encoding = @Encoding(name = "file")))
    @PostMapping(value = "/{epic}/estimation/import", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
    public List<EstimationImportRow> importEstimation(@PathVariable("epic") String epic,
                                            @RequestPart(name = "file", required = false) @Nullable MultipartFile file, Viewer viewer)
            throws IOException {
        scopes.requireVisible(viewer, epic);
        if (file == null) {
            throw new Invalid("Choose a .xlsx or .csv file", "file");
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isEmpty() ? "upload" : file.getOriginalFilename();
        return EstimationFiles.parse(name, file.getBytes());
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

    public record PhaseIn(@NotBlank String phase, @NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
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
}
