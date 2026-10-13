package tracker.domain;

import org.jspecify.annotations.Nullable;

/** Checks for optional text values (null and "" both mean "not set"). */
public final class Text {
    private Text() {
    }

    public static boolean hasText(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

    /** {@code value} when it is set, otherwise {@code fallback}. */
    public static String orElse(@Nullable String value, String fallback) {
        return hasText(value) ? value : fallback;
    }
}
