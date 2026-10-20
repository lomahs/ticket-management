package tracker.domain;

public enum AccessStatus {
    NONE("none"), REQUESTED("requested"), ACTIVE("active"), REJECTED("rejected"), DISABLED("disabled");

    private final String value;

    AccessStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AccessStatus of(String value) {
        return Values.of(AccessStatus.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
