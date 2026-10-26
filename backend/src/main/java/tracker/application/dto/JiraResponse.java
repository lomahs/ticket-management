package tracker.application.dto;

/** Jira's answer to a {@link JiraRequest} that it accepted (2xx). */
public record JiraResponse(int status, String body) {
}
