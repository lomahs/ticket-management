package tracker.domain;

/** Per-user override of "sees every anken": the role default, always, or only the ankens they are a member of. */
public enum AnkenScopeSetting {
    DEFAULT("default"), ALL("all"), MEMBERS("members");

    private final String value;

    AnkenScopeSetting(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AnkenScopeSetting of(String value) {
        return Values.of(AnkenScopeSetting.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
