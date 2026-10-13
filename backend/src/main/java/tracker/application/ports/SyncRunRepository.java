package tracker.application.ports;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

public interface SyncRunRepository {
    /** @param status running | ok | failed */
    record SyncRun(int id, Instant startedAt, @Nullable Instant finishedAt, boolean full, String status, int issues,
                   @Nullable String error) {
    }

    int start(boolean full, Instant at);

    void finish(int runId, Instant at, String status, int issues, @Nullable String error);

    Optional<SyncRun> findLatest();

    Optional<SyncRun> findLatestOk();
}
