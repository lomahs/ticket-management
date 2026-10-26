package tracker.application.ports;

import java.util.List;

import org.jspecify.annotations.Nullable;

/** Every request the app sends to change Jira, kept for the admins. */
public interface JiraWriteLog {
    /**
     * @param source ticket_panel | tickets | add_function | planning
     * @param status Jira's HTTP status; 0 when the request was not sent or Jira could not be reached
     */
    record JiraLogEntry(int id, String at, String actorEmail, String actorName, String source, String key, String method,
                 String path, @Nullable String body, int status, @Nullable String error, long durationMs) {
    }

    void add(JiraLogEntry entry);

    /** Newest first. */
    List<JiraLogEntry> recent(int limit, boolean failedOnly);
}
