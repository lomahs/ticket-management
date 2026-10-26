package tracker.adapters.web;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.service.ScopeService;
import tracker.application.service.TicketCheckService;
import tracker.application.service.TicketCheckService.TicketCheckView;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Viewer;

/** The sub-tasks whose Jira data needs fixing. */
@RestController
@RequestMapping("/api/v1/ticket-check")
public class TicketCheckApi {
    private final TicketCheckService checks;
    private final ScopeService scopes;

    public TicketCheckApi(TicketCheckService checks, ScopeService scopes) {
        this.checks = checks;
        this.scopes = scopes;
    }

    /** @param anken one anken; blank: every anken the viewer sees */
    @Requires(feature = Feature.OVERVIEW, level = Level.READ)
    @GetMapping
    public TicketCheckView view(@RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return checks.view(scopes.scope(viewer, anken));
    }
}
