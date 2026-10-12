package tracker.application.ports;

/** Credentials were rejected. */
public class JiraAuthError extends JiraError {
    public JiraAuthError(String message) {
        super(message);
    }
}
