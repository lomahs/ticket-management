package tracker.application.ports;

import java.util.Collection;
import java.util.Map;

/** A note per ticket, kept in the tracker only. */
public interface TicketNoteRepository {
    /** Ticket key → note, for the keys that have one. */
    Map<String, String> find(Collection<String> issueKeys);

    /** A blank note removes the ticket's note. */
    void save(String issueKey, String note, String updatedBy, String updatedAt);
}
