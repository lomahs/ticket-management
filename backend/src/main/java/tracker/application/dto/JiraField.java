package tracker.application.dto;

/** @param type Jira schema type: date, datetime, number, string, … ("" when Jira gives none) */
public record JiraField(String id, String name, String type) {
}
