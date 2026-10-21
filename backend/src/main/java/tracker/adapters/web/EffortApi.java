package tracker.adapters.web;

import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.service.EffortService.BurndownView;
import tracker.application.service.EffortService.DayDetailView;
import tracker.application.service.EffortService.EffortAnalysis;
import tracker.application.service.EffortService.TimesheetView;
import tracker.application.service.EffortService;
import tracker.application.service.ScopeService;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Viewer;

@RestController
@RequestMapping("/api/v1/effort")
public class EffortApi {
    private final EffortService effort;
    private final ScopeService scopes;

    public EffortApi(EffortService effort, ScopeService scopes) {
        this.effort = effort;
        this.scopes = scopes;
    }

    @Requires(feature = Feature.EFFORT, level = Level.READ)
    @GetMapping("/timesheet")
    public TimesheetView timesheet(@RequestParam("from") @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                                   @RequestParam("to") @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String end,
                                   @RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return effort.timesheet(scopes.scope(viewer, anken), start, end);
    }

    @Requires(feature = Feature.EFFORT, level = Level.READ)
    @GetMapping("/timesheet/{member}/{date}")
    public DayDetailView day(@PathVariable("member") String member,
                             @PathVariable("date") @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String date,
                             @RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return effort.dayDetail(scopes.scope(viewer, anken), member, date);
    }

    /** The anken, which the viewer must see; blank: the first visible one with functions. */
    @Requires(feature = Feature.EFFORT, level = Level.READ)
    @GetMapping("/analysis")
    public EffortAnalysis analysis(@RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return effort.analysis(scopes.scope(viewer, null), anken);
    }

    /** The anken as for the analysis; the anken options are every visible one. */
    @Requires(feature = Feature.EFFORT, level = Level.READ)
    @GetMapping("/burndown")
    public BurndownView burndown(@RequestParam(required = false) @Nullable String anken, @RequestParam(required = false) @Nullable String phase,
                                 @RequestParam(required = false) @Nullable String task, @RequestParam(required = false) @Nullable String member,
                                 Viewer viewer) {
        return effort.burndown(scopes.scope(viewer, null), anken, phase, task, member);
    }
}
