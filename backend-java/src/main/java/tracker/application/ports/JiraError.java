package tracker.application.ports;

/** Jira could not be read or written. (Named as in the original backend: sync errors show the class name.) */
public class JiraError extends RuntimeException {
    public JiraError(String message) {
        super(message);
    }

    public JiraError(String message, Throwable cause) {
        super(message, cause);
    }
}
