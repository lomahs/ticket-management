package tracker.adapters.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.Deps;
import tracker.application.usecases.WorkUseCases;
import tracker.application.usecases.WorkUseCases.MyWork;

@RestController
@RequestMapping("/api/v1")
public class WorkApi {
    private final Deps d;

    public WorkApi(Deps d) {
        this.d = d;
    }

    @GetMapping("/my-work")
    public MyWork myWork(@RequestParam(required = false) String member, @RequestParam(required = false) String backlog) {
        Input in = new Input();
        in.required("member", member);
        String b = backlog == null ? "week" : in.literal("backlog", backlog, List.of("week", "month"));
        in.check();
        return WorkUseCases.myWork(d, member, b);
    }
}
