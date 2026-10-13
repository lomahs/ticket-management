package tracker.adapters.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tracker.TrackerTest;
import tracker.application.ports.SyncRunRepository;
import tracker.application.settings.SettingsStore;

/** The scheduled check syncs when the interval set in Settings → Jira has passed, and never when it is "manual". */
@TrackerTest
class SyncSchedulerTest {
    @Autowired
    SyncScheduler scheduler;
    @Autowired
    SettingsStore settings;
    @Autowired
    SyncRunRepository runs;

    @Test
    void syncsWhenDueOnly() {
        settings.update(s -> {
            s.jira.epics = new java.util.ArrayList<>(List.of("PAY-1"));
            s.jira.interval = "manual";
        });
        scheduler.syncWhenDue();
        assertThat(runs.findLatest()).isEmpty();

        settings.update(s -> s.jira.interval = "15");
        scheduler.syncWhenDue(); // never synced: due now (no site is configured, so the run fails and is recorded)
        int first = runs.findLatest().orElseThrow().id();
        scheduler.syncWhenDue(); // the last run started less than 15 minutes ago
        assertThat(runs.findLatest().orElseThrow().id()).isEqualTo(first);
    }
}
