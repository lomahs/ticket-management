package tracker.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Date helpers. Dates are ISO strings (YYYY-MM-DD) in the domain; they compare lexically. */
public final class Dates {
    private Dates() {
    }

    public static LocalDate parse(String s) {
        return LocalDate.parse(s);
    }

    public static String addDays(String s, long n) {
        return parse(s).plusDays(n).toString();
    }

    /** Sunday = 0 … Saturday = 6 (the convention the working-days setting uses). */
    public static int weekday(String s) {
        return parse(s).getDayOfWeek().getValue() % 7;
    }

    public static String mondayOf(String s) {
        LocalDate d = parse(s);
        return d.minusDays(d.getDayOfWeek().getValue() - 1).toString();
    }

    /** dd/mm label, used in human-readable messages produced by the domain. */
    public static String dm(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(8, 10) + "/" + s.substring(5, 7);
    }

    /** Inclusive range a..b. */
    public static List<String> daysBetween(String a, String b) {
        List<String> out = new ArrayList<>();
        String s = a;
        while (s.compareTo(b) <= 0) {
            out.add(s);
            s = addDays(s, 1);
        }
        return out;
    }

    /** Number of Mon–Fri days in the half-open range (a, b]. */
    public static int weekdaysAfter(String a, String b) {
        if (b.compareTo(a) <= 0) {
            return 0;
        }
        int n = 0;
        for (String s : daysBetween(addDays(a, 1), b)) {
            int w = weekday(s);
            if (w != 0 && w != 6) {
                n++;
            }
        }
        return n;
    }

    public static List<String> monthDays(String month) {
        YearMonth ym = YearMonth.parse(month);
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= ym.lengthOfMonth(); i++) {
            out.add(String.format("%s-%02d", month, i));
        }
        return out;
    }

    public static int dayDiff(String a, String b) {
        return (int) ChronoUnit.DAYS.between(parse(a), parse(b));
    }

    public static double roundHalfUp(double x) {
        return Math.floor(x + 0.5);
    }

    public static double oneDecimal(double n) {
        return roundHalfUp(n * 10) / 10;
    }

    public static String max(String a, String b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
