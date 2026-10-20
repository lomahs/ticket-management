package tracker.domain;

public enum Feature {
    MY_WORK("my_work"), OVERVIEW("overview"), MATRIX("matrix"), TICKET("ticket"), PLANNING("planning"), EFFORT("effort"), QA("qa"), CALENDAR("calendar"), MEMBERS("members");

    private final String value;

    Feature(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Feature of(String value) {
        return Values.of(Feature.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
