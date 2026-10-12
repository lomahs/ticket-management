package tracker.domain;

public enum EventField {
    ASSIGNEE("assignee"), STATUS("status");

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
