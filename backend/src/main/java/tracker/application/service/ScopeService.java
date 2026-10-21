package tracker.application.service;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.NotFound;
import tracker.application.settings.SettingsStore;
import tracker.domain.AnkenScope;
import tracker.domain.Viewer;

/** Which ankens (epics) a viewer may see: the tracked epics narrowed by their access. */
@Service
public class ScopeService {
    private final SettingsStore settings;

    public ScopeService(SettingsStore settings) {
        this.settings = settings;
    }

    /** The tracked epics the viewer sees, in tracked order. */
    public Set<String> visible(Viewer v) {
        Set<String> out = new LinkedHashSet<>();
        for (String epic : settings.load().jira.epics) {
            if (v.sees(epic)) {
                out.add(epic);
            }
        }
        return out;
    }

    /** A blank {@code anken} is every visible epic; otherwise that epic, which must be visible. */
    public AnkenScope scope(Viewer v, @Nullable String anken) {
        Set<String> visible = visible(v);
        if (anken == null || anken.isBlank()) {
            return new AnkenScope(visible);
        }
        if (!visible.contains(anken)) {
            throw new NotFound("Anken " + anken + " is not tracked", "anken");
        }
        return new AnkenScope(Set.of(anken));
    }

    public void requireVisible(Viewer v, String epic) {
        if (!visible(v).contains(epic)) {
            throw new NotFound("Anken " + epic + " is not tracked", "epic");
        }
    }
}
