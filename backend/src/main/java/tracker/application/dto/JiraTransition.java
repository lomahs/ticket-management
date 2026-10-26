package tracker.application.dto;

/**
 * A workflow transition that Jira offers from an issue's current status.
 *
 * @param toCategory new | indeterminate | done
 */
public record JiraTransition(String id, String name, String toName, String toCategory) {
}
