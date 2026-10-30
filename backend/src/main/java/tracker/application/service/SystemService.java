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

import tracker.application.JiraCalls;
import tracker.application.SyncService;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.SyncRunRepository.SyncRun;
import tracker.application.ports.SyncRunRepository;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.EventField;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;

/** App-wide reads (mode, capabilities, members, sync status) and running a sync. */
@Service
public class SystemService {

    /** demo: a simulated Jira site; jira: the team's site, which admins write to through reviewed changes. */
    public record Mode(String name) {
        public boolean demo() {
            return "demo".equals(name);
        }
    }

    /**
     * @param jiraWrite    admins can change tickets in Jira (reviewed and logged)
     * @param planningPush Planning can push its moves to Jira (the demo site only, for now)
     */
    public record Capabilities(boolean jiraWrite, boolean planningPush) {
    }

    /** @param status running | ok | failed | null (never synced) */
    public record SyncStatus(@Nullable String status, @Nullable Instant startedAt, @Nullable Instant finishedAt,
                             @Nullable String error, @Nullable Instant lastOkAt) {
    }

    public record Meta(String mode, String today, Capabilities capabilities, SyncStatus sync, List<String> projects) {
    }

    /**
     * A member as /members lists them. {@code people}: one of the people of the scope, whom the screens may pick (assign,
     * plan, give leave); the others are only named because an issue in scope refers to them.
     */
    public record ScopeMember(String id, String name, String fullName, @Nullable Role role, String country, String color,
                              String email, Permission permission, boolean external, @Nullable String jiraAccountId,
                              boolean people) {
        static ScopeMember of(Member m, boolean people) {
            return new ScopeMember(m.id(), m.name(), m.fullName(), m.role(), m.country(), m.color(), m.email(), m.permission(),
                    m.external(), m.jiraAccountId(), people);
        }
    }

    private final Mode mode;
    private final JiraCalls calls;
    private final AnkenRepository anken;
    private final SyncRunRepository runs;
    private final SyncService sync;
    private final TrackerContextLoader context;

    public SystemService(Mode mode, JiraCalls calls, AnkenRepository anken, SyncRunRepository runs, SyncService sync,
                         TrackerContextLoader context) {
        this.mode = mode;
        this.calls = calls;
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
        return new Meta(mode.name(), context.today(), new Capabilities(calls.enabled(), calls.enabled() && mode.demo()), syncStatus(), projects);
    }

    /**
     * The people of the scope plus every member an issue in scope refers to (assignee, worker, function owner and reviewer,
     * worklog author, either side of an assignee change), ordered by id. The screens use it to show names, and pick only
     * the people.
     */
    public List<ScopeMember> members(AnkenScope scope) {
        TrackerContext ctx = context.load(scope);
        Set<String> people = new HashSet<>(ctx.people);
        Set<String> ids = new HashSet<>(people);
        for (Issue i : ctx.issues) {
            ids.add(i.assigneeId());
            ids.add(ctx.worker(i));
            i.worklogs().forEach(w -> ids.add(w.memberId()));
            i.events().stream().filter(e -> e.field() == EventField.ASSIGNEE).forEach(e -> {
                ids.add(e.fromValue());
                ids.add(e.toValue());
            });
        }
        for (Function f : ctx.functions) {
            ids.add(f.ownerId());
            ids.add(f.balId(ctx.members));
        }
        return ctx.members.values().stream().filter(m -> ids.contains(m.id())).sorted(Comparator.comparing(Member::id))
                .map(m -> ScopeMember.of(m, people.contains(m.id()))).toList();
    }

    public SyncStatus runSync(boolean full) {
        sync.run(full);
        return syncStatus();
    }
}
