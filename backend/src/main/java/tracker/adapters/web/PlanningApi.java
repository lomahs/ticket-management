package tracker.adapters.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.service.PlanningService.PlanChange;
import tracker.application.service.PlanningService.PlanningPreview;
import tracker.application.service.PlanningService.PlanningView;
import tracker.application.service.PlanningService;
import tracker.application.service.ScopeService;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Viewer;

@RestController
@RequestMapping("/api/v1/planning")
public class PlanningApi {
    private final PlanningService planning;
    private final ScopeService scopes;

    public PlanningApi(PlanningService planning, ScopeService scopes) {
        this.planning = planning;
        this.scopes = scopes;
    }

    public record PlanChangeIn(@NotNull String key,
                               @NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                               @NotNull String memberId) {
        PlanChange change() {
            return new PlanChange(key, start, memberId);
        }
    }

    public record PlanningPreviewIn(@NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                                    @NotNull List<@NotNull @Valid PlanChangeIn> changes) {
    }

    public record PlanningPushIn(@NotNull List<@NotNull @Valid PlanChangeIn> changes) {
    }

    @Requires(feature = Feature.PLANNING, level = Level.READ)
    @GetMapping("")
    public PlanningView view(@RequestParam @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                             @RequestParam(defaultValue = "2") @Min(1) @Max(2) int weeks,
                             @RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return planning.view(scopes.scope(viewer, anken), start, weeks);
    }

    @Requires(feature = Feature.PLANNING, level = Level.WRITE)
    @PostMapping("/preview")
    public PlanningPreview preview(@RequestBody @Valid PlanningPreviewIn body, @RequestParam(required = false) @Nullable String anken,
                                   Viewer viewer) {
        return planning.preview(scopes.scope(viewer, anken), body.start(), body.changes().stream().map(PlanChangeIn::change).toList());
    }

    @Requires(feature = Feature.PLANNING, level = Level.WRITE)
    @PostMapping("/push")
    public KeysOut push(@RequestBody @Valid PlanningPushIn body, Viewer viewer) {
        return new KeysOut(planning.push(viewer, scopes.scope(viewer, null), body.changes().stream().map(PlanChangeIn::change).toList()));
    }
}
