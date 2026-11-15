package tracker.application.dto;

/**
 * One changelog item. Assignee and reporter changes carry account ids; status changes carry status names; a change of a
 * mapped field ("field:" + its id) carries Jira's raw values (seconds for time tracking).
 *
 * @param at    YYYY-MM-DD
 * @param field "assignee" | "reporter" | "status" | "field:{fieldId}"
 */
public record ChangeSnapshot(String at, String field, String fromValue, String toValue, UserSnapshot fromUser,
                             UserSnapshot toUser) {

    public ChangeSnapshot(String at, String field, String fromValue, String toValue) {
        this(at, field, fromValue, toValue, null, null);
    }
}
