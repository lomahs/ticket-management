package tracker.adapters.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.service.AnkenService;
import tracker.application.service.AnkenService.AnkenCard;
import tracker.application.service.AnkenService.AnkenDetail;
import tracker.application.service.AnkenService.FunctionDraft;
import tracker.application.service.AnkenService.FunctionPreview;
import tracker.application.service.AnkenService.MatrixView;

@RestController
@RequestMapping("/api/v1/anken")
public class AnkenApi {
    private final AnkenService anken;

    public AnkenApi(AnkenService anken) {
        this.anken = anken;
    }

    /** Planned dates that replace the scheduled ones for one step. */
    public record StepOverride(@Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                               @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String end) {
    }

    /** A function to preview: its five steps are scheduled from the hours of "Create". */
    public record FunctionDraftIn(@NotNull String parentKey, @Nullable String name, @NotNull String ba, @NotNull String bal,
                                  @NotNull String jm,
                                  @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") Double createHours,
                                  @Nullable @Size(max = 5) List<@Nullable @Valid StepOverride> overrides) {
    }

    /** A function to create in Jira; the function id is generated when left out. */
    public record FunctionCreateIn(@NotNull String parentKey, @NotEmpty String name, @NotNull String ba, @NotNull String bal,
                                   @NotNull String jm,
                                   @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") Double createHours,
                                   @Nullable @Size(max = 5) List<@Nullable @Valid StepOverride> overrides,
                                   @Nullable @Pattern(regexp = "^[A-Za-z]+\\d+$", message = "must be letters then digits, like K20")
                                   String functionId) {
        FunctionDraftIn draft() {
            return new FunctionDraftIn(parentKey, name, ba, bal, jm, createHours, overrides);
        }
    }

    @GetMapping("")
    public List<AnkenCard> overview() {
        return anken.overview();
    }

    @GetMapping("/{epic}")
    public AnkenDetail detail(@PathVariable("epic") String epic) {
        return anken.detail(epic);
    }

    @GetMapping("/{epic}/matrix")
    public MatrixView matrix(@PathVariable("epic") String epic) {
        return anken.matrix(epic);
    }

    private static FunctionDraft draft(FunctionDraftIn in) {
        List<Map<String, String>> overrides = new ArrayList<>(Collections.nCopies(5, null));
        if (in.overrides() != null) {
            overrides = new ArrayList<>();
            for (StepOverride o : in.overrides()) {
                Map<String, String> m = null;
                if (o != null) {
                    m = new LinkedHashMap<>();
                    if (o.start() != null) {
                        m.put("start", o.start());
                    }
                    if (o.end() != null) {
                        m.put("end", o.end());
                    }
                }
                overrides.add(m);
            }
        }
        return new FunctionDraft(in.parentKey(), in.name() == null ? "" : in.name(), in.ba(), in.bal(), in.jm(), in.createHours(),
                overrides);
    }

    @PostMapping("/{epic}/functions/preview")
    public FunctionPreview previewFunction(@PathVariable("epic") String epic, @RequestBody @Valid FunctionDraftIn body) {
        return anken.previewFunction(epic, draft(body));
    }

    @PostMapping("/{epic}/functions")
    @ResponseStatus(HttpStatus.CREATED)
    public KeysOut createFunction(@PathVariable("epic") String epic, @RequestBody @Valid FunctionCreateIn body) {
        return new KeysOut(anken.createFunction(epic, draft(body.draft()), body.functionId()));
    }
}
