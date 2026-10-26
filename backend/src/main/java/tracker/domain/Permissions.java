package tracker.domain;

import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What a role may do per feature, and the per-user overrides on top of it. */
public final class Permissions {
    private Permissions() {
    }

    /**
     * The role's level for every feature: PM/PIM write all, JM nothing, BA/BAL/no role read all but members. Ticket is read at
     * most: editing a ticket writes to Jira, which only admins do.
     */
    public static Map<Feature, Level> defaults(@Nullable Role role) {
        Map<Feature, Level> levels = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            levels.put(f, defaultLevel(role, f));
        }
        return levels;
    }

    /** The role defaults with each override applied, Ticket capped at read (see {@link #defaults}). */
    public static Map<Feature, Level> effective(@Nullable Role role, Map<Feature, Level> overrides) {
        Map<Feature, Level> levels = defaults(role);
        levels.putAll(overrides);
        if (levels.get(Feature.TICKET) == Level.WRITE) {
            levels.put(Feature.TICKET, Level.READ);
        }
        return levels;
    }

    /** Whether the user sees every anken: the setting decides, and DEFAULT means PM and PIM do. */
    public static boolean allAnken(@Nullable Role role, AnkenScopeSetting s) {
        return switch (s) {
            case ALL -> true;
            case MEMBERS -> false;
            case DEFAULT -> role == Role.PM || role == Role.PIM;
        };
    }

    private static Level defaultLevel(@Nullable Role role, Feature f) {
        if (role == Role.PM || role == Role.PIM) {
            return f == Feature.TICKET ? Level.READ : Level.WRITE;
        }
        if (role == Role.JM) {
            return Level.NONE;
        }
        return f == Feature.MEMBERS ? Level.NONE : Level.READ;
    }
}
