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

    /** Hours as the app keeps them: rounded half-up to hundredths, 1/3 → 0.33. */
    public static double hours(double value) {
        return round(value, 2);
    }

    /**
     * {@code total} hours in {@code n} parts of whole hundredths that add up to the total rounded ({@link #hours}): the first
     * parts take the hundredths left over, 8 in 3 → 2.67, 2.67, 2.66.
     */
    public static double[] split(double total, int n) {
        long cents = Math.round(hours(total) * 100);
        long each = Math.floorDiv(cents, n);
        long rest = cents - each * n;
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            out[k] = (each + (k < rest ? 1 : 0)) / 100.0;
        }
        return out;
    }

    /** Two amounts of hours added, in hundredths: 0.1 + 0.2 → 0.3, not 0.30000000000000004. */
    public static double sum(double a, double b) {
        return hours(a + b);
    }

    /** Hours rounded down to hundredths, for a cap not to go over: 2.6667 → 2.66. */
    public static double floorHours(double value) {
        return Math.floor(value * 100 + 1e-6) / 100;
    }

    /** A number as people write it: "8", "7.5", "0.25". */
    public static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
