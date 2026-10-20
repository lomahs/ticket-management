package tracker.domain;

public enum Level {
    NONE("none"), READ("read"), WRITE("write");

    private final String value;

    Level(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Level of(String value) {
        return Values.of(Level.class, value);
    }

    public boolean atLeast(Level other) {
        return compareTo(other) >= 0;
    }

    @Override
    public String toString() {
        return value;
    }
}
