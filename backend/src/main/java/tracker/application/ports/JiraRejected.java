package tracker.application.ports;

/** Jira refused a write (a 4xx other than 429): the request was wrong for this site, so retrying will not help. */
public class JiraRejected extends JiraError {
    private final int status;

    public JiraRejected(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
