package tracker.adapters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tracker.application.Deps;
import tracker.application.SyncFromJira.SyncResult;
import tracker.application.ports.UnitOfWork;

/** Periodic Jira sync inside the API process, following the sync interval set in Settings. */
public final class SyncScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger("tracker.sync");
    static final long IDLE_CHECK_SECONDS = 60; // how often a "manual" interval is re-read

    private final Deps deps;
    private final Thread thread;

    private SyncScheduler(Deps deps) {
        this.deps = deps;
        this.thread = new Thread(this::loop, "tracker-sync");
        this.thread.setDaemon(true);
    }

    public static SyncScheduler start(Deps deps) {
        SyncScheduler s = new SyncScheduler(deps);
        s.thread.start();
        return s;
    }

    private void loop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String interval;
                try (UnitOfWork u = deps.uow().begin()) {
                    interval = u.settings().load().jira.interval;
                }
                if (interval.equals("manual")) {
                    Thread.sleep(IDLE_CHECK_SECONDS * 1000);
                    continue;
                }
                Thread.sleep(Long.parseLong(interval) * 60 * 1000);
                SyncResult result = deps.sync().run(false);
                if (!result.status().equals("ok")) {
                    log.warn("scheduled sync failed: {}", result.error());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("sync scheduler stopped", e);
        }
    }

    @Override
    public void close() {
        thread.interrupt();
    }
}
