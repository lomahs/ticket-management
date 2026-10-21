package tracker.application.service;

import org.springframework.stereotype.Service;

import tracker.application.settings.SettingsStore;
import tracker.application.settings.TrackerSettings;
import tracker.application.settings.TrackerSettings.AnkenSettings;

/** Gives every tracked epic its own Phases & Tasks and name rules, copied from the default, when it has none yet. */
@Service
public class AnkenSettingsInitializer {
    private final SettingsStore settings;

    public AnkenSettingsInitializer(SettingsStore settings) {
        this.settings = settings;
    }

    /** Returns how many epics got a copy; does not sync. */
    public int ensureAll() {
        TrackerSettings current = settings.load();
        if (current.anken.keySet().containsAll(current.jira.epics)) {
            return 0; // nothing to add: do not write the document (a fresh database stays without a settings row)
        }
        int[] added = {0};
        settings.update(s -> {
            for (String epic : s.jira.epics) {
                if (!s.anken.containsKey(epic)) {
                    s.anken.put(epic, AnkenSettings.copyOf(s.structure, s.nameRules));
                    added[0]++;
                }
            }
        });
        return added[0];
    }
}
