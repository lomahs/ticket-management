package tracker.domain;

/** A tracked Jira epic. */
public record Anken(String key, String project, String title, String dueDate) {
}
