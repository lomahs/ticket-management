package tracker.application.settings;

import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.ports.SettingsRepository;

/** Reads the settings, and changes them atomically (read, modify and write in one transaction). */
@Component
public class SettingsStore {
    private final SettingsRepository repository;

    public SettingsStore(SettingsRepository repository) {
        this.repository = repository;
    }

    public TrackerSettings load() {
        return repository.load();
    }

    @Transactional
    public TrackerSettings update(Consumer<TrackerSettings> change) {
        TrackerSettings s = repository.load();
        change.accept(s);
        repository.save(s);
        return s;
    }
}
