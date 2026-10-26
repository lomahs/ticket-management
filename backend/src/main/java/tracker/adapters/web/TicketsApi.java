package tracker.adapters.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.service.AnkenService.FunctionSteps;
import tracker.application.service.AnkenService;
import tracker.application.service.ScopeService;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.TicketService.ExpectedUpdate;
import tracker.application.service.TicketService.NewTicket;
import tracker.application.service.TicketService.TicketBoard;
import tracker.application.service.TicketService.TicketChangePreview;
import tracker.application.service.TicketService.TicketUpdate;
import tracker.application.service.TicketService.ExpectedChange;
import tracker.application.service.TicketService.TicketChanges;
import tracker.application.service.TicketService.TicketDetail;
import tracker.application.service.TicketService;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Qa;
import tracker.domain.Status;
import tracker.domain.TicketField;
import tracker.domain.Viewer;

@RestController
@RequestMapping("/api/v1")
public class TicketsApi {
    private final TicketService tickets;
    private final AnkenService anken;
    private final ScopeService scopes;

    public TicketsApi(TicketService tickets, AnkenService anken, ScopeService scopes) {
        this.tickets = tickets;
        this.anken = anken;
        this.scopes = scopes;
    }

    /** The wanted values; fields left out (or null) are not changed. */
    public record TicketChangesIn(@Nullable Status status, @Nullable String assigneeId, @Nullable String reporterId,
                                  @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedStart,
                                  @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedEnd,
                                  @Nullable @PositiveOrZero Double estimateH) {
    }

    /** One change as the review showed it. */
    public record ChangeIn(@NotNull TicketField field, @Nullable String before, @NotNull String after) {
    }

    /** The changes of a review, sent back unchanged. */
    public record ApplyIn(@NotEmpty @Size(max = 6) List<@Valid ChangeIn> changes) {
    }

    /** The wanted values of one ticket in a batch; fields left out are not changed. */
    public record UpdateIn(@NotNull String key, @Nullable Status status, @Nullable String assigneeId, @Nullable String reporterId,
                           @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedStart,
                           @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedEnd,
                           @Nullable @PositiveOrZero Double estimateH) {
    }

    /** A Task (subtask false: under the epic) or a sub-task, under an existing Task or one created earlier in the batch. */
    public record NewTicketIn(@NotNull @Size(min = 1, max = 40) String ref, boolean subtask, @Nullable String parentKey, @Nullable String parentRef,
                              @NotNull @Size(max = 255) String summary, @NotNull String assigneeId, @Nullable String reporterId,
                              @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedStart,
                              @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String plannedEnd,
                              @Nullable @PositiveOrZero Double estimateH) {
        NewTicket ticket() {
            return new NewTicket(ref, subtask, parentKey, parentRef, summary, assigneeId, reporterId, plannedStart, plannedEnd, estimateH);
        }
    }

    public record BatchIn(@NotNull String epic, @NotNull @Size(max = 300) List<@NotNull @Valid UpdateIn> updates,
                          @NotNull @Size(max = 300) List<@NotNull @Valid NewTicketIn> creates) {
    }

    /** One ticket's changes as the review showed them. */
    public record ExpectedUpdateIn(@NotNull String key, @NotEmpty @Size(max = 6) List<@Valid ChangeIn> changes) {
    }

    public record BatchApplyIn(@NotNull String epic, @NotNull @Size(max = 300) List<@NotNull @Valid ExpectedUpdateIn> updates,
                               @NotNull @Size(max = 300) List<@NotNull @Valid NewTicketIn> creates) {
    }

    public record QuestionIn(@NotNull @Size(min = 1, max = 500) String q) {
    }

    @Requires(feature = Feature.TICKET, level = Level.READ)
    @GetMapping("/tickets/{key}")
    public TicketDetail detail(@PathVariable("key") String key, Viewer viewer) {
        return tickets.detail(scopes.scope(viewer, null), key);
    }

    /** The five steps of the function the sub-task belongs to; Planning shows them when a block is clicked. */
    @Requires(feature = Feature.TICKET, level = Level.READ)
    @GetMapping("/tickets/{key}/function")
    public FunctionSteps function(@PathVariable("key") String key, Viewer viewer) {
        return anken.functionOf(scopes.scope(viewer, null), key);
    }

    /** Reads the ticket from Jira and shows what would change and the requests that would be sent. Ticket write: admins. */
    @Requires(feature = Feature.TICKET, level = Level.WRITE)
    @PostMapping("/tickets/{key}/changes/preview")
    public TicketChangePreview previewChanges(@PathVariable("key") String key, @RequestBody @Valid TicketChangesIn body, Viewer viewer) {
        return tickets.preview(scopes.scope(viewer, null), key, new TicketChanges(body.status(), body.assigneeId(), body.reporterId(),
                body.plannedStart(), body.plannedEnd(), body.estimateH()));
    }

    /** Applies a review's changes; 409 when Jira changed since the review (nothing is written). Ticket write: admins. */
    @Requires(feature = Feature.TICKET, level = Level.WRITE)
    @PatchMapping("/tickets/{key}")
    public TicketDetail apply(@PathVariable("key") String key, @RequestBody @Valid ApplyIn body, Viewer viewer) {
        return tickets.apply(viewer, scopes.scope(viewer, null), key,
                body.changes().stream().map(c -> new ExpectedChange(c.field(), c.before(), c.after())).toList());
    }

    /** The Tasks and sub-tasks of an anken the viewer sees, for the Tickets screen. */
    @Requires(feature = Feature.TICKET, level = Level.READ)
    @GetMapping("/tickets/board")
    public TicketBoard board(@RequestParam String anken, Viewer viewer) {
        return tickets.board(scopes.scope(viewer, anken), anken);
    }

    /** Reads the changed tickets from Jira and shows what the batch would change and create, with the requests. Admins. */
    @Requires(feature = Feature.TICKET, level = Level.WRITE)
    @PostMapping("/tickets/batch/preview")
    public BatchPreview previewBatch(@RequestBody @Valid BatchIn body, Viewer viewer) {
        return tickets.previewBatch(scopes.scope(viewer, body.epic()), body.epic(),
                body.updates().stream().map(u -> new TicketUpdate(u.key(), new TicketChanges(u.status(), u.assigneeId(), u.reporterId(),
                        u.plannedStart(), u.plannedEnd(), u.estimateH()))).toList(),
                body.creates().stream().map(NewTicketIn::ticket).toList());
    }

    /** Applies a reviewed batch; 409 when any updated ticket changed in Jira since the review (nothing is written). Admins. */
    @Requires(feature = Feature.TICKET, level = Level.WRITE)
    @PostMapping("/tickets/batch/apply")
    public BatchResult applyBatch(@RequestBody @Valid BatchApplyIn body, Viewer viewer) {
        return tickets.applyBatch(viewer, scopes.scope(viewer, body.epic()), body.epic(),
                body.updates().stream().map(u -> new ExpectedUpdate(u.key(),
                        u.changes().stream().map(c -> new ExpectedChange(c.field(), c.before(), c.after())).toList())).toList(),
                body.creates().stream().map(NewTicketIn::ticket).toList());
    }

    @Requires(feature = Feature.QA, level = Level.READ)
    @PostMapping("/qa")
    public Qa.Answer ask(@RequestBody @Valid QuestionIn body, @RequestParam(required = false) @Nullable String anken,
                         Viewer viewer) {
        return tickets.ask(scopes.scope(viewer, anken), body.q());
    }
}
