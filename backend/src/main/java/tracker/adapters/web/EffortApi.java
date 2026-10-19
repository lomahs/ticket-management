package tracker.adapters.web;

import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.service.EffortService.BurndownView;
import tracker.application.service.EffortService.DayDetailView;
import tracker.application.service.EffortService.EffortAnalysis;
import tracker.application.service.EffortService.TimesheetView;
import tracker.application.service.EffortService;

@RestController
@RequestMapping("/api/v1/effort")
public class EffortApi {
    private final EffortService effort;

    public EffortApi(EffortService effort) {
        this.effort = effort;
    }

    @GetMapping("/timesheet")
    public TimesheetView timesheet(@RequestParam("from") @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String start,
                                   @RequestParam("to") @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String end) {
        return effort.timesheet(start, end);
    }

    @GetMapping("/timesheet/{member}/{date}")
    public DayDetailView day(@PathVariable("member") String member,
                             @PathVariable("date") @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String date) {
        return effort.dayDetail(member, date);
    }

    @GetMapping("/analysis")
    public EffortAnalysis analysis(@RequestParam(required = false) @Nullable String anken) {
        return effort.analysis(anken);
    }

    @GetMapping("/burndown")
    public BurndownView burndown(@RequestParam(required = false) @Nullable String anken, @RequestParam(required = false) @Nullable String phase,
                                 @RequestParam(required = false) @Nullable String member) {
        return effort.burndown(anken, phase, member);
    }
}
