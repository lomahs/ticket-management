package tracker.adapters.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.TaskCard;
import tracker.application.service.AnkenService.AnkenCard;
import tracker.application.service.AnkenService.AnkenDetail;
import tracker.application.service.AnkenService.FunctionDraft;
import tracker.application.service.AnkenService.FunctionPreview;
import tracker.application.service.AnkenService.MatrixView;
import tracker.application.service.AnkenService;
import tracker.application.service.ScopeService;
import tracker.domain.AnkenScope;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Viewer;

@RestController
@RequestMapping("/api/v1/anken")
public class AnkenApi {
    private final AnkenService anken;
    private final ScopeService scopes;

    public AnkenApi(AnkenService anken, ScopeService scopes) {
        this.anken = anken;
        this.scopes = scopes;
    }

    /** Only this epic, which the viewer must see. */
    private AnkenScope epicScope(Viewer viewer, String epic) {
        scopes.requireVisible(viewer, epic);
        return new AnkenScope(Set.of(epic));
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

    @Requires(feature = Feature.OVERVIEW, level = Level.READ)
    @GetMapping("")
    public List<AnkenCard> overview(@RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return this.anken.overview(scopes.scope(viewer, anken));
    }

    @Requires(feature = Feature.OVERVIEW, level = Level.READ)
    @GetMapping("/{epic}")
    public AnkenDetail detail(@PathVariable("epic") String epic, Viewer viewer) {
        return anken.detail(epicScope(viewer, epic), epic, viewer.can(Feature.MEMBERS, Level.READ));
    }

    /** The epic's sub-tasks with one schedule label, optionally within one phase group. */
    @Requires(feature = Feature.OVERVIEW, level = Level.READ)
    @GetMapping("/{epic}/tickets")
    public List<TaskCard> tickets(@PathVariable("epic") String epic,
                                  @RequestParam @Pattern(regexp = "overdue|late_start|early_start|late_finish|early_finish|under_burn|over_burn",
                                          message = "must be a schedule label") String label,
                                  @RequestParam(required = false) @Nullable String phase, Viewer viewer) {
        return anken.tickets(epicScope(viewer, epic), epic, label, phase);
    }

    /**
     * @param tasks the Tasks (parent tickets) whose functions are shown; by default every Task of {@code phase}, else of the first
     *              phase group that has functions
     * @param phase used when no Tasks are given
     * @param shown the Tasks the viewer's own layout shows (Setting phase, kept in the browser), used when neither Tasks nor a
     *              phase are given; keys that are no longer Tasks of the epic are skipped
     */
    @Requires(feature = Feature.MATRIX, level = Level.READ)
    @GetMapping("/{epic}/matrix")
    public MatrixView matrix(@PathVariable("epic") String epic, @RequestParam(required = false) @Nullable List<String> tasks,
                             @RequestParam(required = false) @Nullable String phase,
                             @RequestParam(required = false) @Nullable List<String> shown, Viewer viewer) {
        return anken.matrix(epicScope(viewer, epic), epic, tasks, phase, shown);
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

    @Requires(feature = Feature.MATRIX, level = Level.WRITE)
    @PostMapping("/{epic}/functions/preview")
    public FunctionPreview previewFunction(@PathVariable("epic") String epic, @RequestBody @Valid FunctionDraftIn body, Viewer viewer) {
        return anken.previewFunction(epicScope(viewer, epic), epic, draft(body));
    }

    @Requires(feature = Feature.MATRIX, level = Level.WRITE)
    @PostMapping("/{epic}/functions")
    @ResponseStatus(HttpStatus.CREATED)
    public KeysOut createFunction(@PathVariable("epic") String epic, @RequestBody @Valid FunctionCreateIn body, Viewer viewer) {
        return new KeysOut(anken.createFunction(epicScope(viewer, epic), epic, draft(body.draft()), body.functionId()));
    }
}
