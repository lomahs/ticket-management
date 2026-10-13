package tracker.domain;

public enum LeaveType {
    FULL("full"), HALF("half"), FULL_TENTATIVE("fullTent"), HALF_TENTATIVE("halfTent");

    private final String value;

    LeaveType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static LeaveType of(String value) {
        return Values.of(LeaveType.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
