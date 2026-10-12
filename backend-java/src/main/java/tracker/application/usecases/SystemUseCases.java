package tracker.application.usecases;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

import tracker.application.Deps;
import tracker.application.ports.Repositories.SyncRun;
import tracker.application.ports.UnitOfWork;
import tracker.domain.Anken;
import tracker.domain.Member;

/** App-wide reads: mode and capabilities, members, sync status; running a sync. */
public final class SystemUseCases {
    private SystemUseCases() {
    }

    public record Capabilities(boolean jiraWrite) {
    }

    /** @param status running | ok | failed | null (never synced) */
    public record SyncStatus(String status, Instant startedAt, Instant finishedAt, String error, Instant lastOkAt) {
    }

    public record Meta(String mode, String today, Capabilities capabilities, SyncStatus sync, List<String> projects) {
    }

    private static SyncStatus status(SyncRun latest, SyncRun ok) {
        return new SyncStatus(latest != null ? latest.status() : null, latest != null ? latest.startedAt() : null,
                latest != null ? latest.finishedAt() : null, latest != null ? latest.error() : null,
                ok != null ? ok.finishedAt() : null);
    }

    public static SyncStatus syncStatus(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            return status(u.syncRuns().latest(), u.syncRuns().latestOk());
        }
    }

    public static Meta meta(Deps d) {
        List<String> projects;
        SyncStatus st;
        try (UnitOfWork u = d.uow().begin()) {
            projects = List.copyOf(new TreeSet<>(u.anken().list().stream().map(Anken::project).toList()));
            st = status(u.syncRuns().latest(), u.syncRuns().latestOk());
        }
        return new Meta(d.mode(), d.clock().today(), new Capabilities(d.writer() != null), st, projects);
    }

    public static List<Member> members(Deps d) {
        try (UnitOfWork u = d.uow().begin()) {
            return u.members().list();
        }
    }

    public static SyncStatus runSync(Deps d, boolean full) {
        d.sync().run(full);
        return syncStatus(d);
    }
}
