package tracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rounding of hours and ratios for display. */
public final class Numbers {
    private Numbers() {
    }

    /** {@code value} rounded half-up to {@code scale} decimals. */
    public static double round(double value, int scale) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** A number as people write it: "8", "7.5", "0.25". */
    public static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
