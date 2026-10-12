package tracker.adapters.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import tracker.application.AppError.Invalid;
import tracker.application.Deps;
import tracker.application.usecases.CalendarUseCases;
import tracker.application.usecases.CalendarUseCases.CalendarView;
import tracker.application.usecases.CalendarUseCases.ImportPreview;
import tracker.application.usecases.CalendarUseCases.ImportResult;
import tracker.application.usecases.CalendarUseCases.LeaveDraft;
import tracker.domain.LeaveImport.ImportRow;
import tracker.domain.LeaveType;

@RestController
@RequestMapping("/api/v1")
public class CalendarApi {
    static final int MAX_UPLOAD = 5 * 1024 * 1024;
    private static final List<String> LEAVE_TYPES = List.of("full", "half", "fullTent", "halfTent");
    private final Deps d;

    public CalendarApi(Deps d) {
        this.d = d;
    }

    public record DaysOut(int days) {
    }

    public record ImportPreviewOut(String file, List<ImportRow> rows, int workingDays) {
    }

    private static String month(Input in, String month) {
        return in.pattern("month", in.required("month", month), Input.MONTH);
    }

    @GetMapping("/calendar")
    public CalendarView month(@RequestParam(required = false) String month) {
        Input in = new Input();
        month(in, month);
        in.check();
        return CalendarUseCases.month(d, month);
    }

    /** LeaveIn. */
    private static LeaveDraft leave(Input in, Integer editId, byte[] raw) {
        Input.Obj b = in.body(raw);
        if (b == null) {
            in.check();
        }
        String member = b.str("member_id");
        String type = b.has("type") ? in.asChoice(b.raw("type"), b.at("type"), LEAVE_TYPES, false) : b.str("type");
        String from = in.pattern(b.at("date_from"), b.str("date_from"), Input.ISO_DATE);
        String to = in.pattern(b.at("date_to"), b.str("date_to"), Input.ISO_DATE);
        String note = b.str("note", "");
        in.check();
        return new LeaveDraft(editId, member, LeaveType.of(type), from, to, note);
    }

    private static Integer leaveId(Input in, String raw) {
        return in.integer("leave_id", raw);
    }

    @PostMapping("/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    public DaysOut addLeave(@RequestBody(required = false) byte[] raw) {
        return new DaysOut(CalendarUseCases.saveLeave(d, leave(new Input(), null, raw)));
    }

    @PutMapping("/leaves/{leaveId}")
    public DaysOut editLeave(@PathVariable String leaveId, @RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Integer id = leaveId(in, leaveId);
        return new DaysOut(CalendarUseCases.saveLeave(d, leave(in, id, raw)));
    }

    @DeleteMapping("/leaves/{leaveId}")
    public ResponseEntity<Void> deleteLeave(@PathVariable String leaveId) {
        Input in = new Input();
        Integer id = leaveId(in, leaveId);
        in.check();
        CalendarUseCases.deleteLeave(d, id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).contentType(MediaType.APPLICATION_JSON).build();
    }

    @PostMapping("/leaves/import/preview")
    public ImportPreviewOut importPreview(HttpServletRequest request, @RequestParam(required = false) String sample) throws IOException {
        Input in = new Input();
        boolean useSample = in.bool("sample", sample, false);
        in.check();
        String name;
        List<List<String>> rows;
        MultipartFile file = request instanceof MultipartHttpServletRequest m ? m.getFile("file") : null;
        if (useSample) {
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
        ImportPreview p = CalendarUseCases.importPreview(d, rows);
        return new ImportPreviewOut(name, p.rows(), p.workingDays());
    }

    @PostMapping("/leaves/import")
    public ImportResult importConfirm(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = in.body(raw);
        List<List<String>> rows = null;
        if (b != null) {
            rows = b.list("rows", (at, v) -> in.asList(v, at, (at2, x) -> in.asString(x, at2, false)));
            in.maxItems(b.at("rows"), rows, 2000);
        }
        in.check();
        return CalendarUseCases.importConfirm(d, rows);
    }

    @GetMapping("/leaves/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"leave-import-template.csv\"")
                .body(LeaveFiles.TEMPLATE_CSV.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/leaves/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month) {
        Input in = new Input();
        month(in, month);
        in.check();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"team-calendar-" + month + ".xls\"")
                .body(LeaveFiles.exportXls(CalendarUseCases.export(d, month)).getBytes(StandardCharsets.UTF_8));
    }
}
