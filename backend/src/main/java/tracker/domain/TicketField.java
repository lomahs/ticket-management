package tracker.domain;

/** A field of a ticket the app may change in Jira. */
public enum TicketField {
    STATUS("status"), ASSIGNEE("assignee"), REPORTER("reporter"), PLANNED_START("planned_start"), PLANNED_END("planned_end"),
    ESTIMATE("estimate");

    private final String value;

    TicketField(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TicketField of(String value) {
        return Values.of(TicketField.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
