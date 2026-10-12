package tracker.domain;

public enum Permission {
    ADMIN("admin"), USER("user");

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Permission of(String value) {
        return Values.of(Permission.class, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
