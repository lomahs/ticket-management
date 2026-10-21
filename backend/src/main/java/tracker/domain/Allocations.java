package tracker.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/** Allocation rows with their effective-date rule: the latest row not after a day gives the hours on that day. */
public final class Allocations {
    private final List<Allocation> rows;

    public Allocations(List<Allocation> rows) {
        this.rows = List.copyOf(rows);
    }

    /** Hours per day on {@code date}, or null when no row of this member and anken starts on or before it. */
    public @Nullable Double hoursOn(String memberId, String epic, String date) {
        Allocation best = null;
        for (Allocation a : rows) {
            if (a.memberId().equals(memberId) && a.epicKey().equals(epic) && a.effectiveFrom().compareTo(date) <= 0
                    && (best == null || a.effectiveFrom().compareTo(best.effectiveFrom()) > 0)) {
                best = a;
            }
        }
        return best == null ? null : best.hoursPerDay();
    }

    public boolean hasAny(String memberId, String epic) {
        return rows.stream().anyMatch(a -> a.memberId().equals(memberId) && a.epicKey().equals(epic));
    }

    /** Sum over every anken of the hours on {@code date}. */
    public double totalOn(String memberId, String date) {
        Set<String> epics = new TreeSet<>();
        for (Allocation a : rows) {
            if (a.memberId().equals(memberId)) {
                epics.add(a.epicKey());
            }
        }
        double total = 0;
        for (String epic : epics) {
            total += Objects.requireNonNullElse(hoursOn(memberId, epic, date), 0.0);
        }
        return total;
    }

    /** The distinct effective dates of this member, ascending. */
    public List<String> datesOf(String memberId) {
        return rows.stream().filter(a -> a.memberId().equals(memberId)).map(Allocation::effectiveFrom).distinct().sorted().toList();
    }
}
