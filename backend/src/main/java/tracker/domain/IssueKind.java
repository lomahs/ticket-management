package tracker.domain;

public enum IssueKind {
    PARENT("parent"), SUBTASK("subtask");

    private final String value;

    IssueKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static IssueKind of(String value) {
        return Values.of(IssueKind.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
