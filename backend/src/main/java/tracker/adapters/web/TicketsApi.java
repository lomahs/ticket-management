package tracker.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tracker.application.service.TicketService.TicketDetail;
import tracker.application.service.TicketService.TicketPatch;
import tracker.application.service.TicketService;
import tracker.domain.Qa;
import tracker.domain.Status;

@RestController
@RequestMapping("/api/v1")
public class TicketsApi {
    private final TicketService tickets;

    public TicketsApi(TicketService tickets) {
        this.tickets = tickets;
    }

    /** Fields left out (or null) are not changed. */
    public record TicketPatchIn(@Nullable Status status, @Nullable String assigneeId,
                                @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedStart,
                                @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedEnd) {
    }

    public record QuestionIn(@NotNull @Size(min = 1, max = 500) String q) {
    }

    @GetMapping("/tickets/{key}")
    public TicketDetail detail(@PathVariable("key") String key) {
        return tickets.detail(key);
    }

    @PatchMapping("/tickets/{key}")
    public TicketDetail patch(@PathVariable("key") String key, @RequestBody @Valid TicketPatchIn body) {
        return tickets.patch(key, new TicketPatch(body.status(), body.assigneeId(), body.plannedStart(), body.plannedEnd()));
    }

    @PostMapping("/qa")
    public Qa.Answer ask(@RequestBody @Valid QuestionIn body) {
        return tickets.ask(body.q());
    }
}
