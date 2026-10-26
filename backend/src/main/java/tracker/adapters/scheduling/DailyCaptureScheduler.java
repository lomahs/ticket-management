package tracker.adapters.scheduling;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tracker.application.SyncService;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.SettingsRepository;
import tracker.application.service.DailyService;
import tracker.application.settings.TrackerSettings;
import tracker.domain.AnkenScope;

/**
 * Captures the Daily Overview of every tracked anken once the time set in Settings has passed on a working day: one sync
 * first, so the capture shows fresh data, then each anken that has no capture today ("off": never).
 */
@Component
@Profile("!demo")
class DailyCaptureScheduler {
    private static final Logger log = LoggerFactory.getLogger(DailyCaptureScheduler.class);

    private final SettingsRepository settings;
    private final SyncService sync;
    private final DailyService daily;
    private final TrackerContextLoader context;
    private final Clock clock;

    DailyCaptureScheduler(SettingsRepository settings, SyncService sync, DailyService daily, TrackerContextLoader context, Clock clock) {
        this.settings = settings;
        this.sync = sync;
        this.daily = daily;
        this.context = context;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${tracker.sync.check-every:PT1M}", fixedDelayString = "${tracker.sync.check-every:PT1M}")
    void captureWhenDue() {
        TrackerSettings s = settings.load();
        String time = s.dailyCapture;
        if (time.equals("off") || LocalDateTime.now(clock).toLocalTime().isBefore(LocalTime.parse(time))
                || !context.calendar(s, Map.of(), List.of()).visible(context.today())) {
            return;
        }
        List<String> due = daily.uncaptured();
        if (due.isEmpty()) {
            return;
        }
        SyncService.SyncResult result = sync.run(false);
        if (!result.status().equals("ok")) {
            log.warn("sync before the daily capture failed, capturing the stored data: {}", result.error());
        }
        AnkenScope all = new AnkenScope(Set.copyOf(settings.load().jira.epics));
        for (String epic : due) {
            try {
                daily.capture(all, epic, "auto");
            } catch (RuntimeException e) {
                log.warn("daily capture of {} failed", epic, e);
            }
        }
    }
}
