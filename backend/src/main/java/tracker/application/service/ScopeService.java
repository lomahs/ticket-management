package tracker.application.service;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.NotFound;
import tracker.application.ports.OnboardingRepository;
import tracker.application.settings.SettingsStore;
import tracker.domain.AnkenScope;
import tracker.domain.DraftAnken;
import tracker.domain.Onboarding;
import tracker.domain.Viewer;

/**
 * Which ankens (epics) a viewer may see: the tracked epics narrowed by their access. A draft anken ({@link DraftAnken}) is seen
 * by the admin only, and only where it is named: Onboarding.
 */
@Service
public class ScopeService {
    private final SettingsStore settings;
    private final OnboardingRepository onboarding;

    public ScopeService(SettingsStore settings, OnboardingRepository onboarding) {
        this.settings = settings;
        this.onboarding = onboarding;
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

    /** The draft anken being onboarded, for the admin; none for anyone else. */
    public Set<String> drafts(Viewer v) {
        Set<String> out = new LinkedHashSet<>();
        if (v.admin()) {
            onboarding.findAll().stream().filter(Onboarding::draft).forEach(o -> out.add(o.epicKey()));
        }
        return out;
    }

    /** {@link #visible} and the {@link #drafts}. */
    public Set<String> visibleWithDrafts(Viewer v) {
        Set<String> out = visible(v);
        out.addAll(drafts(v));
        return out;
    }

    /** The epic must be visible, or a draft anken the viewer works on. */
    public void requireVisible(Viewer v, String epic) {
        if (!visible(v).contains(epic) && !(DraftAnken.isKey(epic) && drafts(v).contains(epic))) {
            throw new NotFound("Anken " + epic + " is not tracked", "epic");
        }
    }
}
