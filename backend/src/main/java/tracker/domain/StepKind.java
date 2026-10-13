package tracker.domain;

public enum StepKind {
    CREATE("create"), BAL_REVIEW("balr"), FIX_BAL("fixb"), JM_REVIEW("jmr"), FIX_JM("fixj");

    private final String value;

    StepKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static StepKind of(String value) {
        return Values.of(StepKind.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
