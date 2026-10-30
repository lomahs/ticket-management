package tracker.application;

import org.jspecify.annotations.Nullable;

import tracker.domain.IssueSchedule;

/**
 * One issue as screens show it: raw values plus the flags the domain derived.
 *
 * @param workerId    the legacy worker, for Planning only
 * @param planOwnerId who the plan belongs to by the step's role; null on JM Review
 */
public record TaskCard(
        String key, String summary, String project, String epicKey, String phase, @Nullable String functionId, @Nullable String functionName,
        @Nullable String step, String status, String jiraStatus, @Nullable String assigneeId, @Nullable String workerId,
        @Nullable String planOwnerId, @Nullable String plannedStart,
        @Nullable String plannedEnd, double estimateH, double loggedH, double remainingH, @Nullable String actualStart, @Nullable String actualEnd,
        boolean isCurrent, boolean late, int lateDays, boolean soon, boolean over, String tone, int heldDays, int rounds,
        IssueSchedule schedule) {
}
