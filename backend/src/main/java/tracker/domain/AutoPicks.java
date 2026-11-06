package tracker.domain;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** Who Auto allocate put in each slot of an estimation row; null where it put nobody or the pick was changed by hand. */
public record AutoPicks(long rowId, @Nullable String baId, @Nullable String balId, @Nullable String jmId) {
    public static AutoPicks none(long rowId) {
        return new AutoPicks(rowId, null, null, null);
    }

    public boolean empty() {
        return baId == null && balId == null && jmId == null;
    }

    /** Whether {@code picked}, the slot's person, was picked by hand: someone Auto allocate did not put there. */
    public static boolean byHand(@Nullable String picked, @Nullable String auto) {
        return picked != null && !Objects.equals(picked, auto);
    }
}
