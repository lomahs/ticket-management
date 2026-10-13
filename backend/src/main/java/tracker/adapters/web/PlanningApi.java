package tracker.adapters.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.service.PlanningService.PlanChange;
import tracker.application.service.PlanningService.PlanningPreview;
import tracker.application.service.PlanningService.PlanningView;
import tracker.application.service.PlanningService;

@RestController
@RequestMapping("/api/v1/planning")
public class PlanningApi {
    private final PlanningService planning;

    public PlanningApi(PlanningService planning) {
        this.planning = planning;
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

    @GetMapping("")
    public PlanningView view(@RequestParam @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                             @RequestParam(defaultValue = "2") @Min(1) @Max(2) int weeks) {
        return planning.view(start, weeks);
    }

    @PostMapping("/preview")
    public PlanningPreview preview(@RequestBody @Valid PlanningPreviewIn body) {
        return planning.preview(body.start(), body.changes().stream().map(PlanChangeIn::change).toList());
    }

    @PostMapping("/push")
    public KeysOut push(@RequestBody @Valid PlanningPushIn body) {
        return new KeysOut(planning.push(body.changes().stream().map(PlanChangeIn::change).toList()));
    }
}
