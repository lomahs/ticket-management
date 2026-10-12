package tracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.DoubleStream;

/**
 * Number helpers with the Python semantics the original backend used, so every derived figure stays identical:
 * sum() is CPython's compensated float sum, round() rounds half to even on the exact binary value.
 */
public final class Num {
    private Num() {
    }

    /** CPython 3.12+ sum() of floats (Neumaier compensated summation). */
    public static double sum(DoubleStream values) {
        double[] acc = {0.0, 0.0}; // running sum, compensation
        values.forEachOrdered(x -> {
            double s = acc[0];
            double t = s + x;
            if (Math.abs(s) >= Math.abs(x)) {
                acc[1] += (s - t) + x;
            } else {
                acc[1] += (x - t) + s;
            }
            acc[0] = t;
        });
        return acc[1] != 0.0 && Double.isFinite(acc[1]) ? acc[0] + acc[1] : acc[0];
    }

    /** Python round(x): nearest integer, ties to even. */
    public static int round(double x) {
        return (int) Math.rint(x);
    }

    /** Python round(x, n). */
    public static double round(double x, int n) {
        if (!Double.isFinite(x)) {
            return x;
        }
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).doubleValue();
    }
}
