package tracker.application.ports;

import tracker.application.settings.TrackerSettings;

public interface SettingsRepository {
    /** The stored settings, or the defaults when none were saved. */
    TrackerSettings load();

    void save(TrackerSettings settings);
}
