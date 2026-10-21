package tracker.adapters.web;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.service.AllocationService;
import tracker.application.service.AllocationService.AllocationChange;
import tracker.application.service.AllocationService.AllocationTimeline;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Viewer;

/** The hours per day a member gives each anken, from an effective date. */
@RestController
@RequestMapping("/api/v1")
public class AllocationApi {
    private final AllocationService service;

    public AllocationApi(AllocationService service) {
        this.service = service;
    }

    public record AllocationIn(@NotNull @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String from,
                               @NotNull Map<String, @NotNull Double> hours) {
    }

    @Requires(feature = Feature.MEMBERS, level = Level.READ)
    @GetMapping("/people/{memberId}/allocations")
    public AllocationTimeline timeline(@PathVariable("memberId") String memberId, Viewer viewer) {
        return service.timeline(viewer, memberId);
    }

    @Requires(feature = Feature.MEMBERS, level = Level.WRITE)
    @PutMapping("/people/{memberId}/allocations")
    public AllocationTimeline set(@PathVariable("memberId") String memberId, @RequestBody @Valid AllocationIn body, Viewer viewer) {
        return service.set(viewer, memberId, new AllocationChange(body.from(), body.hours()));
    }
}
