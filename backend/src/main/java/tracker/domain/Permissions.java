package tracker.domain;

import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What a role may do per feature, and the per-user overrides on top of it. */
public final class Permissions {
    private Permissions() {
    }

    /** The role's level for every feature: PM/PIM write all, JM nothing, BA/BAL/no role read all but members. */
    public static Map<Feature, Level> defaults(@Nullable Role role) {
        Map<Feature, Level> levels = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            levels.put(f, defaultLevel(role, f));
        }
        return levels;
    }

    /** The role defaults with each override applied. */
    public static Map<Feature, Level> effective(@Nullable Role role, Map<Feature, Level> overrides) {
        Map<Feature, Level> levels = defaults(role);
        levels.putAll(overrides);
        return levels;
    }

    private static Level defaultLevel(@Nullable Role role, Feature f) {
        if (role == Role.PM || role == Role.PIM) {
            return Level.WRITE;
        }
        if (role == Role.JM) {
            return Level.NONE;
        }
        return f == Feature.MEMBERS ? Level.NONE : Level.READ;
    }
}
