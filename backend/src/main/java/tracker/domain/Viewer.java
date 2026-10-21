package tracker.domain;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The signed-in user as the screens see them: who, which member, and what they may do. */
public record Viewer(String email, String name, @Nullable String memberId, @Nullable Role role, boolean admin,
        AccessStatus access, Map<Feature, Level> levels, boolean allAnken, Set<String> epics) {

    public Viewer(String email, String name, @Nullable String memberId, @Nullable Role role, boolean admin, AccessStatus access,
            Map<Feature, Level> levels) {
        this(email, name, memberId, role, admin, access, levels, false, Set.of());
    }

    public boolean active() {
        return access == AccessStatus.ACTIVE;
    }

    public boolean can(Feature f, Level l) {
        if (!active()) {
            return false;
        }
        return admin || levels.get(f).atLeast(l);
    }

    /** Whether the viewer may see this anken. */
    public boolean sees(String epic) {
        if (!active()) {
            return false;
        }
        return admin || allAnken || epics.contains(epic);
    }
}
