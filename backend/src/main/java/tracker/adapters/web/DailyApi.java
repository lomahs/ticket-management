package tracker.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.auth.Requires;
import tracker.application.service.DailyService;
import tracker.application.service.DailyService.DailyView;
import tracker.application.service.ScopeService;
import tracker.domain.Feature;
import tracker.domain.Level;
import tracker.domain.Viewer;

/** The Daily Overview of one anken: live for today, captured per day. */
@RestController
@RequestMapping("/api/v1/daily")
public class DailyApi {
    private final DailyService daily;
    private final ScopeService scopes;

    public DailyApi(DailyService daily, ScopeService scopes) {
        this.daily = daily;
        this.scopes = scopes;
    }

    /** @param date a captured day; blank or today: live */
    @Requires(feature = Feature.OVERVIEW, level = Level.READ)
    @GetMapping
    public DailyView view(@RequestParam("anken") String anken,
                          @RequestParam(required = false) @Nullable @Pattern(regexp = Formats.DATE, message = Formats.DATE_MESSAGE) String date,
                          Viewer viewer) {
        return daily.view(scopes.scope(viewer, anken), anken, date);
    }

    /** Captures today, replacing an earlier capture of today. */
    @Requires(feature = Feature.OVERVIEW, level = Level.WRITE)
    @PostMapping("/snapshots")
    public DailyView capture(@RequestParam("anken") String anken, Viewer viewer) {
        return daily.capture(scopes.scope(viewer, anken), anken, viewer.memberId() != null ? viewer.memberId() : viewer.email());
    }

    /** @param note a blank note removes it */
    public record NoteIn(@NotNull @Size(max = 2000) String note) {
    }

    public record NoteOut(String key, String note) {
    }

    @Requires(feature = Feature.OVERVIEW, level = Level.WRITE)
    @PutMapping("/notes/{key}")
    public NoteOut note(@PathVariable("key") String key, @RequestBody @Valid NoteIn body, Viewer viewer) {
        return new NoteOut(key, daily.saveNote(scopes.scope(viewer, null), key, body.note(),
                viewer.memberId() != null ? viewer.memberId() : viewer.email()));
    }
}
