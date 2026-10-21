package tracker.adapters.web;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Open.Rule;
import tracker.adapters.web.auth.Open;
import tracker.application.service.SystemService.Meta;
import tracker.application.service.SystemService.ScopeMember;
import tracker.application.service.SystemService.SyncStatus;
import tracker.application.service.ScopeService;
import tracker.application.service.SystemService;
import tracker.domain.Viewer;

@RestController
@RequestMapping("/api/v1")
public class SystemApi {
    private final SystemService system;
    private final ScopeService scopes;

    public SystemApi(SystemService system, ScopeService scopes) {
        this.system = system;
        this.scopes = scopes;
    }

    @Open(Rule.ACTIVE)
    @GetMapping("/meta")
    public Meta meta(Viewer viewer) {
        return system.meta(scopes.scope(viewer, null));
    }

    /** The people of the viewer's anken (or of {@code anken}) and every member their issues refer to. */
    @Open(Rule.ACTIVE)
    @GetMapping("/members")
    public List<ScopeMember> members(@RequestParam(required = false) @Nullable String anken, Viewer viewer) {
        return system.members(scopes.scope(viewer, anken));
    }

    /** @param full a full sync (all issues of the epics) instead of the ones changed since the last sync */
    public record SyncIn(@Nullable Boolean full) {
    }

    @Open(Rule.ACTIVE)
    @PostMapping("/sync")
    public SyncStatus sync(@RequestBody SyncIn body) {
        return system.runSync(Boolean.TRUE.equals(body.full()));
    }

    @Open(Rule.ACTIVE)
    @GetMapping("/sync/runs/latest")
    public SyncStatus latestSync() {
        return system.syncStatus();
    }
}
