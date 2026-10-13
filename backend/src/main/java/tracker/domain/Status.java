package tracker.domain;

/** Tracker status of an issue: NYS (not yet started), WIP, Resolved, Closed. */
public enum Status {
    NYS("N"), WIP("W"), RESOLVED("R"), CLOSED("C");

    private final String value;

    Status(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Status of(String value) {
        return Values.of(Status.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
