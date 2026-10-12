package tracker.application.dto;

/** Jira data as the JiraReader port delivers it: normalised, but still in Jira's vocabulary (accounts, status names). */
public record UserSnapshot(String accountId, String displayName, String email) {

    public UserSnapshot(String accountId, String displayName) {
        this(accountId, displayName, "");
    }
}
