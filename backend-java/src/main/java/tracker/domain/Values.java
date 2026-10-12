package tracker.domain;

/** Lookup of string-valued enums by value; their toString() is the value, as in the API and the database. */
public final class Values {
    private Values() {
    }

    /** The constant whose value is {@code value}; IllegalArgumentException "'x' is not a valid Name" otherwise. */
    public static <E extends Enum<E>> E of(Class<E> type, String value) {
        for (E e : type.getEnumConstants()) {
            if (e.toString().equals(value)) {
                return e;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid " + type.getSimpleName());
    }
}
