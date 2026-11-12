package tracker.domain;

/**
 * Schedule labels of one sub-task. {@code startDays} / {@code finishDays} are working days early, late or overdue (else 0);
 * the hours are rounded to 2 decimals and are 0 when the effort label is no_plan or no_estimate.
 *
 * @param planRemainH   what the plan leaves at the end of today
 * @param actualRemainH Jira's remaining estimate; 0 once Closed
 * @param diffH         actual remain − plan remain: positive is behind the plan
 */
public record IssueSchedule(StartState start, int startDays, FinishState finish, int finishDays, EffortState effort,
                            double planRemainH, double actualRemainH, double diffH) {
}
