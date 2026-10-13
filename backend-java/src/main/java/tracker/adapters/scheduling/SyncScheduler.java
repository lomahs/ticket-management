package tracker.adapters.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tracker.application.SyncService;
import tracker.application.ports.SettingsRepository;
import tracker.application.ports.SyncRunRepository;
import tracker.application.ports.SyncRunRepository.SyncRun;

/** Runs an incremental sync when the interval set in Settings → Jira has passed since the last sync ("manual": never). */
@Component
@Profile("!demo")
class SyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SettingsRepository settings;
    private final SyncRunRepository runs;
    private final SyncService sync;
    private final Clock clock;

    SyncScheduler(SettingsRepository settings, SyncRunRepository runs, SyncService sync, Clock clock) {
        this.settings = settings;
        this.runs = runs;
        this.sync = sync;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${tracker.sync.check-every:PT1M}", fixedDelayString = "${tracker.sync.check-every:PT1M}")
    void syncWhenDue() {
        String interval = settings.load().jira.interval;
        if (interval.equals("manual") || settings.load().jira.epics.isEmpty()) {
            return;
        }
        Instant last = runs.findLatest().map(SyncRun::startedAt).orElse(Instant.EPOCH);
        if (Instant.now(clock).isBefore(last.plus(Duration.ofMinutes(Long.parseLong(interval))))) {
            return;
        }
        SyncService.SyncResult result = sync.run(false);
        if (!result.status().equals("ok")) {
            log.warn("scheduled sync failed: {}", result.error());
        }
    }
}
