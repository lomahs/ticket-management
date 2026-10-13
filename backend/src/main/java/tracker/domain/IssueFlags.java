package tracker.domain;

public record IssueFlags(boolean isCurrent, boolean late, int lateDays, boolean soon, boolean over, String tone,
                         int heldDays, int rounds) {
}
