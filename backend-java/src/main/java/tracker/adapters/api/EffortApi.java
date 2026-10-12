package tracker.adapters.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.Deps;
import tracker.application.usecases.EffortUseCases;
import tracker.application.usecases.EffortUseCases.DayDetailView;
import tracker.application.usecases.EffortUseCases.EffortAnalysis;
import tracker.application.usecases.EffortUseCases.TimesheetView;

@RestController
@RequestMapping("/api/v1/effort")
public class EffortApi {
    private final Deps d;

    public EffortApi(Deps d) {
        this.d = d;
    }

    @GetMapping("/timesheet")
    public TimesheetView timesheet(@RequestParam(name = "from", required = false) String start,
                                   @RequestParam(name = "to", required = false) String end) {
        Input in = new Input();
        in.pattern("from", in.required("from", start), Input.ISO_DATE);
        in.pattern("to", in.required("to", end), Input.ISO_DATE);
        in.check();
        return EffortUseCases.timesheet(d, start, end);
    }

    @GetMapping("/timesheet/{member}/{date}")
    public DayDetailView day(@PathVariable String member, @PathVariable String date) {
        Input in = new Input();
        in.pattern("date", date, Input.ISO_DATE);
        in.check();
        return EffortUseCases.dayDetail(d, member, date);
    }

    @GetMapping("/analysis")
    public EffortAnalysis analysis(@RequestParam(required = false) String anken) {
        return EffortUseCases.analysis(d, anken);
    }
}
