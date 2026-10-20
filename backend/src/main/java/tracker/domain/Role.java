package tracker.domain;

public enum Role {
    BA("BA"), PM("PM"), PIM("PIM"), BAL("BAL"), JM("JM");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Role of(String value) {
        return Values.of(Role.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
