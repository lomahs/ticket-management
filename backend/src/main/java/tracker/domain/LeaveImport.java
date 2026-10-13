package tracker.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** Validation of leave rows imported from Excel/CSV (Member, From, To, Type, Note). */
public final class LeaveImport {
    private LeaveImport() {
    }

    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * @param row   spreadsheet row number (header is row 1)
     * @param error empty when valid
     */
    public record ImportRow(int row, String member, String dateFrom, String dateTo, String typeText, String note,
                            @Nullable String memberId, @Nullable LeaveType type, String error) {
    }

    private static String cell(List<?> raw, int i) {
        Object v = i < raw.size() ? raw.get(i) : "";
        return v == null ? "" : String.valueOf(v).strip();
    }

    public static List<ImportRow> validateRows(List<? extends List<?>> rows, Map<String, Member> members, Iterable<Leave> existing) {
        Set<String> taken = new HashSet<>();
        existing.forEach(l -> taken.add(l.memberId() + "\u0000" + l.date()));
        List<ImportRow> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<?> raw = rows.get(i);
            String mn = cell(raw, 0), f = cell(raw, 1), t = cell(raw, 2), ty = cell(raw, 3), note = cell(raw, 4);
            String key = mn.toLowerCase(Locale.ROOT);
            String mid = members.entrySet().stream()
                    .filter(e -> e.getValue().name().toLowerCase(Locale.ROOT).equals(key) || e.getKey().equals(key))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            LeaveType tk = LeaveTypeInfo.LEAVE_TYPES.entrySet().stream()
                    .filter(e -> e.getValue().label().toLowerCase(Locale.ROOT).equals(ty.toLowerCase(Locale.ROOT))
                            || e.getKey().value().toLowerCase(Locale.ROOT).equals(ty.toLowerCase(Locale.ROOT)))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            String to = Text.orElse(t, f);
            String err;
            if (mid == null) {
                err = "Unknown member \"" + mn + "\"";
            } else if (!ISO.matcher(f).matches() || !ISO.matcher(to).matches()) {
                err = "Invalid date format, use YYYY-MM-DD";
            } else if (to.compareTo(f) < 0) {
                err = "To date is before From date";
            } else if (tk == null) {
                err = "Unknown leave type \"" + ty + "\"";
            } else {
                String m = mid;
                String clash = Dates.daysBetween(f, to).stream().filter(d -> taken.contains(m + "\u0000" + d)).findFirst().orElse(null);
                err = clash != null ? "Overlaps existing leave on " + Dates.dm(clash) : "";
            }
            out.add(new ImportRow(i + 2, mn, f, to, ty, note, mid, tk, err));
        }
        return out;
    }
}
