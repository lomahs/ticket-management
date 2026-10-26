package tracker.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tracker.application.AppError.CapabilityDisabled;
import tracker.application.dto.JiraRequest;
import tracker.application.dto.JiraResponse;
import tracker.application.ports.JiraError;
import tracker.application.ports.JiraRejected;
import tracker.application.ports.JiraWriteLog;
import tracker.application.ports.JiraWriter;
import tracker.domain.Viewer;

/** Sends requests that change Jira and logs every one of them, whatever Jira answers. */
@Component
public class JiraCalls {
    public static final String TICKET_PANEL = "ticket_panel";
    public static final String ADD_FUNCTION = "add_function";
    public static final String PLANNING = "planning";
    public static final String TICKETS = "tickets";

    private final Optional<JiraWriter> writer;
    private final JiraWriteLog log;
    private final Clock clock;

    public JiraCalls(Optional<JiraWriter> writer, JiraWriteLog log, Clock clock) {
        this.writer = writer;
        this.log = log;
        this.clock = clock;
    }

    public boolean enabled() {
        return writer.isPresent();
    }

    /** @throws CapabilityDisabled when the site cannot be written to */
    public void requireEnabled() {
        if (writer.isEmpty()) {
            throw new CapabilityDisabled("Writing to Jira is not available");
        }
    }

    /** @param source where the change was made: {@link #TICKET_PANEL}, {@link #TICKETS}, {@link #ADD_FUNCTION} or {@link #PLANNING} */
    public JiraResponse send(Viewer actor, String source, String issueKey, JiraRequest request) {
        requireEnabled();
        long started = System.nanoTime();
        try {
            JiraResponse r = writer.get().send(request);
            record(actor, source, issueKey, request, r.status(), null, started);
            return r;
        } catch (JiraRejected e) {
            record(actor, source, issueKey, request, e.status(), e.getMessage(), started);
            throw e;
        } catch (JiraError e) {
            record(actor, source, issueKey, request, 0, e.getMessage(), started);
            throw e;
        }
    }

    /** Logs a request that was not sent, with the reason. */
    public void notSent(Viewer actor, String source, String issueKey, JiraRequest request, String reason) {
        log.add(new JiraWriteLog.JiraLogEntry(0, Instant.now(clock).toString(), actor.email(), actor.name(), source, issueKey, request.method(),
                request.path(), request.body(), 0, "Not sent: " + reason, 0));
    }

    private void record(Viewer actor, String source, String issueKey, JiraRequest request, int status, String error, long started) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        log.add(new JiraWriteLog.JiraLogEntry(0, Instant.now(clock).toString(), actor.email(), actor.name(), source, issueKey, request.method(),
                request.path(), request.body(), status, error, ms));
    }
}
