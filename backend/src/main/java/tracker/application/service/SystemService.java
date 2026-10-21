package tracker.application.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.SyncService;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.JiraWriter;
import tracker.application.ports.SyncRunRepository.SyncRun;
import tracker.application.ports.SyncRunRepository;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Member;

/** App-wide reads (mode, capabilities, members, sync status) and running a sync. */
@Service
public class SystemService {

    /** demo: a simulated Jira site with writes; jira: the team's site, read-only in phase 1. */
    public record Mode(String name) {
    }

    public record Capabilities(boolean jiraWrite) {
    }

    /** @param status running | ok | failed | null (never synced) */
    public record SyncStatus(@Nullable String status, @Nullable Instant startedAt, @Nullable Instant finishedAt,
                             @Nullable String error, @Nullable Instant lastOkAt) {
    }

    public record Meta(String mode, String today, Capabilities capabilities, SyncStatus sync, List<String> projects) {
    }

    private final Mode mode;
    private final Optional<JiraWriter> writer;
    private final AnkenRepository anken;
    private final SyncRunRepository runs;
    private final SyncService sync;
    private final TrackerContextLoader context;

    public SystemService(Mode mode, Optional<JiraWriter> writer, AnkenRepository anken, SyncRunRepository runs, SyncService sync,
                         TrackerContextLoader context) {
        this.mode = mode;
        this.writer = writer;
        this.anken = anken;
        this.runs = runs;
        this.sync = sync;
        this.context = context;
    }

    public SyncStatus syncStatus() {
        Optional<SyncRun> latest = runs.findLatest();
        return new SyncStatus(latest.map(SyncRun::status).orElse(null), latest.map(SyncRun::startedAt).orElse(null),
                latest.map(SyncRun::finishedAt).orElse(null), latest.map(SyncRun::error).orElse(null),
                runs.findLatestOk().map(SyncRun::finishedAt).orElse(null));
    }

    /** {@code projects} are those of the anken in scope. */
    public Meta meta(AnkenScope scope) {
        List<String> projects = List.copyOf(new TreeSet<>(anken.findAll().stream().filter(a -> scope.contains(a.key()))
                .map(Anken::project).toList()));
        return new Meta(mode.name(), context.today(), new Capabilities(writer.isPresent()), syncStatus(), projects);
    }

    /**
     * The people of the scope plus every member an issue in scope refers to (assignee, worker, function owner and reviewer,
     * worklog author), ordered by id.
     */
    public List<Member> members(AnkenScope scope) {
        TrackerContext ctx = context.load(scope);
        Set<String> ids = new HashSet<>(ctx.people);
        for (Issue i : ctx.issues) {
            ids.add(i.assigneeId());
            ids.add(ctx.worker(i));
            i.worklogs().forEach(w -> ids.add(w.memberId()));
        }
        for (Function f : ctx.functions) {
            ids.add(f.ownerId());
            ids.add(f.reviewerId());
        }
        return ctx.members.values().stream().filter(m -> ids.contains(m.id())).sorted(Comparator.comparing(Member::id)).toList();
    }

    public SyncStatus runSync(boolean full) {
        sync.run(full);
        return syncStatus();
    }
}
