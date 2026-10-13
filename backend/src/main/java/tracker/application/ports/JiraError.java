package tracker.application.ports;

/** Jira could not be read or written. A failed sync records the class name with the message. */
public class JiraError extends RuntimeException {
    public JiraError(String message) {
        super(message);
    }

    public JiraError(String message, Throwable cause) {
        super(message, cause);
    }
}
