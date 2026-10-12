package tracker.adapters.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.api.AnkenApi.KeysOut;
import tracker.application.Deps;
import tracker.application.usecases.PlanningUseCases;
import tracker.application.usecases.PlanningUseCases.PlanChange;
import tracker.application.usecases.PlanningUseCases.PlanningPreview;
import tracker.application.usecases.PlanningUseCases.PlanningView;

@RestController
@RequestMapping("/api/v1/planning")
public class PlanningApi {
    private final Deps d;

    public PlanningApi(Deps d) {
        this.d = d;
    }

    @GetMapping("")
    public PlanningView view(@RequestParam(required = false) String start, @RequestParam(required = false) String weeks) {
        Input in = new Input();
        in.pattern("start", in.required("start", start), Input.ISO_DATE);
        Integer w = weeks == null ? Integer.valueOf(2) : in.integer("weeks", weeks);
        in.range("weeks", w, 1.0, 2.0);
        in.check();
        return PlanningUseCases.view(d, start, w);
    }

    private static List<PlanChange> changes(Input in, Input.Obj b) {
        return b.list("changes", (at, v) -> {
            Input.Obj c = in.model(v, at);
            if (c == null) {
                return null;
            }
            String key = c.str("key");
            String start = in.pattern(c.at("start"), c.str("start"), Input.ISO_DATE);
            String member = c.str("member_id");
            return new PlanChange(key, start, member);
        });
    }

    @PostMapping("/preview")
    public PlanningPreview preview(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = in.body(raw);
        String start = null;
        List<PlanChange> changes = null;
        if (b != null) {
            start = in.pattern(b.at("start"), b.str("start"), Input.ISO_DATE);
            changes = changes(in, b);
        }
        in.check();
        return PlanningUseCases.preview(d, start, changes);
    }

    @PostMapping("/push")
    public KeysOut push(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = in.body(raw);
        List<PlanChange> changes = b != null ? changes(in, b) : null;
        in.check();
        return new KeysOut(PlanningUseCases.push(d, changes));
    }
}
