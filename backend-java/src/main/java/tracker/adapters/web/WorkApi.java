package tracker.adapters.web;

import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

import tracker.application.service.WorkService;
import tracker.application.service.WorkService.MyWork;

@RestController
@RequestMapping("/api/v1")
public class WorkApi {
    private final WorkService work;

    public WorkApi(WorkService work) {
        this.work = work;
    }

    @GetMapping("/my-work")
    public MyWork myWork(@RequestParam String member,
                         @RequestParam(defaultValue = "week") @Pattern(regexp = "^(week|month)$", message = "must be week or month")
                         @Parameter(schema = @Schema(allowableValues = {"week", "month"})) String backlog) {
        return work.myWork(member, backlog);
    }
}
