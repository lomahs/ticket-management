package tracker.domain;

/** Remaining hours against the plan remain ({@link ScheduleRules#effortOf}). */
public enum EffortState {
    /** Delay: more left than the plan leaves. */
    UNDER_BURN("under_burn"),
    /** Ahead: less left than the plan leaves. */
    OVER_BURN("over_burn"),
    ON_PLAN("on_plan"),
    NO_PLAN("no_plan"),
    NO_ESTIMATE("no_estimate");

    private final String value;

    EffortState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static EffortState of(String value) {
        return Values.of(EffortState.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
