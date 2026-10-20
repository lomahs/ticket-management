package tracker.domain;

public enum RequestStatus {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected");

    private final String value;

    RequestStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RequestStatus of(String value) {
        return Values.of(RequestStatus.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
