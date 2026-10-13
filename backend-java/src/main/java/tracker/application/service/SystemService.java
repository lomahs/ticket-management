package tracker.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.SyncService;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.JiraWriter;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SyncRunRepository;
import tracker.application.ports.SyncRunRepository.SyncRun;
import tracker.domain.Anken;
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
    private final MemberRepository members;
    private final AnkenRepository anken;
    private final SyncRunRepository runs;
    private final SyncService sync;
    private final TrackerContextLoader context;

    public SystemService(Mode mode, Optional<JiraWriter> writer, MemberRepository members, AnkenRepository anken,
                         SyncRunRepository runs, SyncService sync, TrackerContextLoader context) {
        this.mode = mode;
        this.writer = writer;
        this.members = members;
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

    public Meta meta() {
        List<String> projects = List.copyOf(new TreeSet<>(anken.findAll().stream().map(Anken::project).toList()));
        return new Meta(mode.name(), context.today(), new Capabilities(writer.isPresent()), syncStatus(), projects);
    }

    public List<Member> members() {
        return members.findAll();
    }

    public SyncStatus runSync(boolean full) {
        sync.run(full);
        return syncStatus();
    }
}
