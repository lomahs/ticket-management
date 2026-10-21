package tracker.domain;

public enum FinishState {
    EARLY_FINISH("early_finish"),
    LATE_FINISH("late_finish"),
    ON_TIME_FINISH("on_time_finish"),
    OVERDUE("overdue"),
    OPEN("open"),
    NO_PLAN("no_plan");

    private final String value;

    FinishState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FinishState of(String value) {
        return Values.of(FinishState.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
