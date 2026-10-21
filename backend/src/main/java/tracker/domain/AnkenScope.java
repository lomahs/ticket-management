package tracker.domain;

import java.util.Set;

/** The ankens (epic keys) a user may see. */
public record AnkenScope(Set<String> epics) {
    public boolean contains(String epic) {
        return epics.contains(epic);
    }
}
