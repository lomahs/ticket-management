package tracker.application.ports;

/** Readers for credentials that are not saved yet (the "Test connection" button). */
@FunctionalInterface
public interface JiraReaderFactory {
    JiraReader create(String url, String email, String token);
}
