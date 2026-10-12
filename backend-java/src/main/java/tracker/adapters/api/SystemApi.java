package tracker.adapters.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.Deps;
import tracker.application.usecases.SystemUseCases;
import tracker.application.usecases.SystemUseCases.Meta;
import tracker.application.usecases.SystemUseCases.SyncStatus;
import tracker.domain.Member;

@RestController
@RequestMapping("/api/v1")
public class SystemApi {
    private final Deps d;

    public SystemApi(Deps d) {
        this.d = d;
    }

    @GetMapping("/meta")
    public Meta meta() {
        return SystemUseCases.meta(d);
    }

    @GetMapping("/members")
    public List<Member> members() {
        return SystemUseCases.members(d);
    }

    @PostMapping("/sync")
    public SyncStatus sync(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = in.body(raw);
        Boolean full = b != null ? b.bool("full", false) : null;
        in.check();
        return SystemUseCases.runSync(d, full);
    }

    @GetMapping("/sync/runs/latest")
    public SyncStatus latestSync() {
        return SystemUseCases.syncStatus(d);
    }
}
