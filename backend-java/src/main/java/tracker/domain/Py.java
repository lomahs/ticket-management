package tracker.domain;

import java.util.Collection;
import java.util.Map;

/** Python truthiness and str() for the loosely typed values the original backend handled. */
public final class Py {
    private Py() {
    }

    public static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** a if it is a non-empty string, else b (Python's {@code a or b}). */
    public static String or(String a, String b) {
        return truthy(a) ? a : b;
    }

    /** str(v) for JSON-like values. */
    public static String str(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Double || v instanceof Float) {
            return repr(((Number) v).doubleValue());
        }
        return v.toString();
    }

    /** repr() of a float: shortest round-trip digits, always with a fractional part or exponent. */
    public static String repr(double x) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        if (x == Math.rint(x) && Math.abs(x) < 1e16) {
            return (long) x + ".0";
        }
        String s = Double.toString(x);
        if (s.contains("E")) {
            double abs = Math.abs(x);
            if (abs >= 1e-4 && abs < 1e16) {
                return new java.math.BigDecimal(s).toPlainString();
            }
            String[] parts = s.split("E");
            int exp = Integer.parseInt(parts[1]);
            String mant = parts[0].endsWith(".0") ? parts[0].substring(0, parts[0].length() - 2) : parts[0];
            return mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
        }
        return s;
    }

    /** float(v) for a JSON value or a numeric string. */
    public static double toFloat(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip().replace("_", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("could not convert string to float: '" + s + "'");
            }
        }
        throw new IllegalArgumentException("float() argument must be a string or a real number, not '"
                + (v == null ? "NoneType" : v.getClass().getSimpleName()) + "'");
    }
}
