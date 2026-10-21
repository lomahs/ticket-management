package tracker.adapters.web;

import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

import tracker.adapters.web.auth.Requires;
import tracker.application.service.WorkService.MyWork;
import tracker.application.service.ScopeService;
import tracker.application.service.WorkService;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Viewer;

@RestController
@RequestMapping("/api/v1")
public class WorkApi {
    private final WorkService work;
    private final ScopeService scopes;

    public WorkApi(WorkService work, ScopeService scopes) {
        this.work = work;
        this.scopes = scopes;
    }

    @Requires(feature = Feature.MY_WORK, level = Level.READ)
    @GetMapping("/my-work")
    public MyWork myWork(@RequestParam String member,
                         @RequestParam(defaultValue = "week") @Pattern(regexp = "^(week|month)$", message = "must be week or month")
                         @Parameter(schema = @Schema(allowableValues = {"week", "month"})) String backlog,
                         @RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return work.myWork(scopes.scope(viewer, anken), member, backlog);
    }
}
