package tracker.domain;

public enum StartState {
    EARLY_START("early_start"),
    LATE_START("late_start"),
    ON_TIME_START("on_time_start"),
    NOT_DUE("not_due"),
    NO_PLAN("no_plan");

    private final String value;

    StartState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static StartState of(String value) {
        return Values.of(StartState.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
