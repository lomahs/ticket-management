package tracker.domain;

public enum EventField {
    ASSIGNEE("assignee"), STATUS("status"),
    /** A change of the Original Estimate (the field mapped as Planned Effort), in hours. */
    ESTIMATE("estimate"),
    /** A change of the Remaining Estimate (the field mapped as Remaining), in hours: a worklog's or a re-estimate. */
    REMAINING("remaining");

    private final String value;

    EventField(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static EventField of(String value) {
        return Values.of(EventField.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
