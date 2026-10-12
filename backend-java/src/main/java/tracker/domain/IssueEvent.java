package tracker.domain;

/** One changelog entry. For STATUS events the values are tracker statuses (N/W/R/C). */
public record IssueEvent(String at, EventField field, String fromValue, String toValue) {
}
