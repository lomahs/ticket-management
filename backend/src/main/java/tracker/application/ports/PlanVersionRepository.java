package tracker.application.ports;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/** Snapshots of an anken's detail plan, saved by hand. */
public interface PlanVersionRepository {
    record SavedVersion(String savedAt, String body) {
    }

    /** @param withEstimates the body holds each sub-task's Original Estimate (a version that can be the baseline) */
    void add(String epic, String savedAt, String savedBy, String body, boolean withEstimates);

    /** When the epic's last version was saved, or null. */
    @Nullable String lastSavedAt(String epic);

    /** The Baseline plan: the epic's first version with estimates. */
    Optional<SavedVersion> baseline(String epic);
}
