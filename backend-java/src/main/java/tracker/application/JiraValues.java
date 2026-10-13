package tracker.application;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/** Values of Jira fields as the REST API returns them: text, numbers, or null when unset. */
public final class JiraValues {
    private JiraValues() {
    }

    /** Fields that hold seconds; the tracker works in hours. */
    public static final Set<String> SECONDS_FIELDS = Set.of("timeoriginalestimate", "aggregatetimeoriginalestimate", "timeestimate");

    public static boolean isSet(@Nullable Object value) {
        return value != null && !(value instanceof String s && s.isEmpty());
    }

    /** The date part (YYYY-MM-DD) of a date or date-time field. */
    public static @Nullable String date(@Nullable Object value) {
        if (!isSet(value)) {
            return null;
        }
        String s = String.valueOf(value);
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    /** A number field as hours. */
    public static @Nullable Double hours(@Nullable Object value, String fieldId) {
        if (!isSet(value)) {
            return null;
        }
        double n = value instanceof Number num ? num.doubleValue() : Double.parseDouble(String.valueOf(value).strip());
        return SECONDS_FIELDS.contains(fieldId) ? n / 3600 : n;
    }
}
