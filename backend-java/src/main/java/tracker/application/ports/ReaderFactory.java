package tracker.application.ports;

/** url, email, token → reader, for testing unsaved credentials. */
@FunctionalInterface
public interface ReaderFactory {
    JiraReader create(String url, String email, String token);
}
