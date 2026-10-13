package tracker.adapters.web;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.service.SystemService;
import tracker.application.service.SystemService.Meta;
import tracker.application.service.SystemService.SyncStatus;
import tracker.domain.Member;

@RestController
@RequestMapping("/api/v1")
public class SystemApi {
    private final SystemService system;

    public SystemApi(SystemService system) {
        this.system = system;
    }

    @GetMapping("/meta")
    public Meta meta() {
        return system.meta();
    }

    @GetMapping("/members")
    public List<Member> members() {
        return system.members();
    }

    /** @param full a full sync (all issues of the epics) instead of the ones changed since the last sync */
    public record SyncIn(@Nullable Boolean full) {
    }

    @PostMapping("/sync")
    public SyncStatus sync(@RequestBody SyncIn body) {
        return system.runSync(Boolean.TRUE.equals(body.full()));
    }

    @GetMapping("/sync/runs/latest")
    public SyncStatus latestSync() {
        return system.syncStatus();
    }
}
