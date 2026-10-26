package tracker.application.dto;

/** An issue type a project can create; {@code subtask} marks the sub-task type. */
public record JiraIssueType(String id, String name, boolean subtask) {
}
