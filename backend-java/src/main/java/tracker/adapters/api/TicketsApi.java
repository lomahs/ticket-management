package tracker.adapters.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.Deps;
import tracker.application.usecases.TicketUseCases;
import tracker.application.usecases.TicketUseCases.TicketDetail;
import tracker.application.usecases.TicketUseCases.TicketPatch;
import tracker.domain.Qa;

@RestController
@RequestMapping("/api/v1")
public class TicketsApi {
    private final Deps d;

    public TicketsApi(Deps d) {
        this.d = d;
    }

    @GetMapping("/tickets/{key}")
    public TicketDetail detail(@PathVariable String key) {
        return TicketUseCases.detail(d, key);
    }

    @PatchMapping("/tickets/{key}")
    public TicketDetail patch(@PathVariable String key, @RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = in.body(raw);
        TicketPatch p = null;
        if (b != null) {
            String status = b.has("status") ? in.asChoice(b.raw("status"), b.at("status"), List.of("N", "W", "R", "C"), true) : null;
            String assignee = b.nullableStr("assignee_id");
            String start = in.pattern(b.at("planned_start"), b.nullableStr("planned_start"), Input.ISO_DATE);
            String end = in.pattern(b.at("planned_end"), b.nullableStr("planned_end"), Input.ISO_DATE);
            p = new TicketPatch(status, assignee, start, end);
        }
        in.check();
        return TicketUseCases.patch(d, key, p);
    }

    @PostMapping("/qa")
    public Qa.Answer ask(@RequestBody(required = false) byte[] raw) {
        Input in = new Input();
        Input.Obj b = in.body(raw);
        String q = null;
        if (b != null) {
            q = b.str("q");
            in.minLength(b.at("q"), q, 1);
            in.maxLength(b.at("q"), q, 500);
        }
        in.check();
        return TicketUseCases.ask(d, q);
    }
}
