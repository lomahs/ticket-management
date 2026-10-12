package tracker.application;

/** One issue as screens show it: raw values plus the flags the domain derived. */
public record TaskCard(
        String key, String summary, String project, String epicKey, String phase, String functionId, String functionName,
        String step, String status, String jiraStatus, String assigneeId, String workerId, String plannedStart,
        String plannedEnd, double estimateH, double loggedH, double remainingH, String actualStart, String actualEnd,
        boolean isCurrent, boolean late, int lateDays, boolean soon, boolean over, String tone, int heldDays, int rounds) {
}
