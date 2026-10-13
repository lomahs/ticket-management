package tracker.adapters.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import tracker.application.service.CalendarService;
import tracker.application.AppError.Invalid;
import tracker.application.service.CalendarService.CalendarView;
import tracker.application.service.CalendarService.ImportPreview;
import tracker.application.service.CalendarService.ImportResult;
import tracker.application.service.CalendarService.LeaveDraft;
import tracker.domain.LeaveImport.ImportRow;
import tracker.domain.LeaveType;

@RestController
@RequestMapping("/api/v1")
public class CalendarApi {
    static final int MAX_UPLOAD = 5 * 1024 * 1024;
    private final CalendarService calendar;

    public CalendarApi(CalendarService calendar) {
        this.calendar = calendar;
    }

    public record DaysOut(int days) {
    }

    public record ImportPreviewOut(String file, List<ImportRow> rows, int workingDays) {
    }

    /** Leave on every working day from {@code date_from} to {@code date_to}. */
    public record LeaveIn(@NotNull String memberId, @NotNull LeaveType type,
                          @NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String dateFrom,
                          @NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String dateTo,
                          @Nullable String note) {
        LeaveDraft draft(@Nullable Integer editId) {
            return new LeaveDraft(editId, memberId, type, dateFrom, dateTo, note == null ? "" : note);
        }
    }

    /** @param rows the rows of an import preview: member, from, to, type, note */
    public record ImportIn(@NotNull @Size(max = 2000) List<@NotNull List<@NotNull String>> rows) {
    }

    @GetMapping("/calendar")
    public CalendarView month(@RequestParam @Pattern(regexp = Formats.MONTH, message = Formats.MONTH_MESSAGE) String month) {
        return calendar.month(month);
    }

    @PostMapping("/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    public DaysOut addLeave(@RequestBody @Valid LeaveIn body) {
        return new DaysOut(calendar.saveLeave(body.draft(null)));
    }

    @PutMapping("/leaves/{leave_id}")
    public DaysOut editLeave(@PathVariable("leave_id") int leaveId, @RequestBody @Valid LeaveIn body) {
        return new DaysOut(calendar.saveLeave(body.draft(leaveId)));
    }

    @DeleteMapping("/leaves/{leave_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLeave(@PathVariable("leave_id") int leaveId) {
        calendar.deleteLeave(leaveId);
    }

    /** The multipart body of an import preview. */
    public record ImportUpload(@Nullable @Schema(type = "string", format = "binary") MultipartFile file) {
    }

    /** Reads an uploaded .xlsx/.csv (or, with {@code sample}, a built-in example) and checks every row. */
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = @Schema(implementation = ImportUpload.class), encoding = @Encoding(name = "file")))
    @PostMapping(value = "/leaves/import/preview", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
    public ImportPreviewOut importPreview(@RequestParam(defaultValue = "false") boolean sample,
                                          @RequestPart(name = "file", required = false) @Nullable MultipartFile file)
            throws IOException {
        String name;
        List<List<String>> rows;
        if (sample) {
            name = LeaveFiles.SAMPLE_FILE;
            rows = LeaveFiles.SAMPLE_ROWS;
        } else if (file != null) {
            name = file.getOriginalFilename() == null || file.getOriginalFilename().isEmpty() ? "upload" : file.getOriginalFilename();
            byte[] raw;
            try (InputStream s = file.getInputStream()) {
                raw = s.readNBytes(MAX_UPLOAD + 1);
            }
            if (raw.length > MAX_UPLOAD) {
                throw new Invalid("The file is larger than 5 MB", "file");
            }
            rows = LeaveFiles.readRows(name, raw);
        } else {
            throw new Invalid("Choose a .xlsx or .csv file", "file");
        }
        ImportPreview p = calendar.importPreview(rows);
        return new ImportPreviewOut(name, p.rows(), p.workingDays());
    }

    @PostMapping("/leaves/import")
    public ImportResult importConfirm(@RequestBody @Valid ImportIn body) {
        return calendar.importConfirm(body.rows());
    }

    @GetMapping("/leaves/template")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"leave-import-template.csv\"")
                .body(LeaveFiles.TEMPLATE_CSV.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/leaves/export")
    @ApiResponse(responseCode = "200",
            content = @Content(mediaType = "application/vnd.ms-excel", schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> export(@RequestParam @Pattern(regexp = Formats.MONTH, message = Formats.MONTH_MESSAGE) String month) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"team-calendar-" + month + ".xls\"")
                .body(LeaveFiles.exportXls(calendar.export(month)).getBytes(StandardCharsets.UTF_8));
    }
}
