package tracker.application.ports;

import java.util.List;
import java.util.Optional;

/** The Daily Overview of an anken captured per day; the payload is stored as a document and read back as {@code type}. */
public interface DailySnapshotRepository {
    /** @param capturedBy a member id, or "auto" for the scheduled capture */
    record SnapshotRef(String date, String capturedAt, String capturedBy) {
    }

    /** An upsert on (epic, date): a later capture on the same day replaces the earlier one. */
    void save(String epicKey, String date, String capturedAt, String capturedBy, Object payload);

    <T> Optional<T> find(String epicKey, String date, Class<T> type);

    /** The epic's snapshots, newest first. */
    List<SnapshotRef> list(String epicKey);
}
