package tracker.application.ports;

import org.jspecify.annotations.Nullable;

/** Snapshots of an anken's detail plan, saved by hand. */
public interface PlanVersionRepository {
    void add(String epic, String savedAt, String savedBy, String body);

    /** When the epic's last version was saved, or null. */
    @Nullable String lastSavedAt(String epic);
}
