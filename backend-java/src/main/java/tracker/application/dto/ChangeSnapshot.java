package tracker.application.dto;

/**
 * One changelog item. Assignee changes carry account ids; status changes carry status names.
 *
 * @param at    YYYY-MM-DD
 * @param field "assignee" | "status"
 */
public record ChangeSnapshot(String at, String field, String fromValue, String toValue, UserSnapshot fromUser,
                             UserSnapshot toUser) {

    public ChangeSnapshot(String at, String field, String fromValue, String toValue) {
        this(at, field, fromValue, toValue, null, null);
    }
}
