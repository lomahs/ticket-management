package tracker.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tracker.application.dto.ChangeSnapshot;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.UserSnapshot;
import tracker.application.dto.WorklogSnapshot;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraReader;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SettingsRepository;
import tracker.application.ports.SyncRunRepository.SyncRun;
import tracker.application.ports.SyncRunRepository;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.IssueKind;
import tracker.domain.Member;
import tracker.domain.MemberIds;
import tracker.domain.Naming;
import tracker.domain.Permission;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Text;
import tracker.domain.Worklog;

/**
 * Copies the tracked epics from Jira into the database. Everything is read from Jira first; the changes are then
 * written in one short transaction, so a slow Jira never holds the database's write lock.
 */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    /** Incremental syncs re-read what changed a little before the last good run started. */
    static final Duration INCREMENTAL_MARGIN = Duration.ofMinutes(5);
    public static final Map<String, Status> CATEGORY_STATUS = Map.of(
            "new", Status.NYS, "indeterminate", Status.WIP, "done", Status.CLOSED);

    /** Jira returned nothing for epics that already have issues here: kept the data rather than wiping it. */
    public static class EmptySyncException extends RuntimeException {
        EmptySyncException(String message) {
            super(message);
        }
    }

    /** @param status ok | failed */
    public record SyncResult(int runId, String status, int issues, @Nullable String error) {
    }

    private record Fetched(List<EpicSnapshot> epics, List<IssueSnapshot> issues) {
    }

    /** One sync at a time (scheduler, Sync button, settings changes). */
    private final ReentrantLock running = new ReentrantLock();
    private final JiraReader reader;
    private final SettingsRepository settings;
    private final MemberRepository members;
    private final IssueRepository issues;
    private final AnkenRepository anken;
    private final SyncRunRepository runs;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SyncService(JiraReader reader, SettingsRepository settings, MemberRepository members, IssueRepository issues,
                       AnkenRepository anken, SyncRunRepository runs, TransactionTemplate tx, Clock clock) {
        this.reader = reader;
        this.settings = settings;
        this.members = members;
        this.issues = issues;
        this.anken = anken;
        this.runs = runs;
        this.tx = tx;
        this.clock = clock;
    }

    /** Syncs all issues of the tracked epics ({@code full}) or those changed since the last good sync. */
    public SyncResult run(boolean full) {
        requireNoTransaction();
        running.lock();
        try {
            Optional<SyncRun> lastOk = runs.findLatestOk();
            int runId = runs.start(full, Instant.now(clock));
            try {
                Instant since = full ? null : lastOk.map(r -> r.startedAt().minus(INCREMENTAL_MARGIN)).orElse(null);
                List<String> epics = List.copyOf(settings.load().jira.epics);
                Fetched fetched = fetch(epics, since);
                int n = tx.execute(status -> apply(epics, fetched, full));
                runs.finish(runId, Instant.now(clock), "ok", n, null);
                return new SyncResult(runId, "ok", n, null);
            } catch (RuntimeException e) { // recorded on the run; the stored data stays as it was
                String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("sync {} failed: {}", runId, error);
                runs.finish(runId, Instant.now(clock), "failed", 0, error);
                return new SyncResult(runId, "failed", 0, error);
            }
        } finally {
            running.unlock();
        }
    }

    /** Re-reads specific issues after writing them to Jira; keys Jira no longer has are skipped. */
    public void refresh(List<String> keys) {
        requireNoTransaction();
        running.lock();
        try {
            List<IssueSnapshot> snaps = keys.stream().map(reader::findIssue).flatMap(Optional::stream).toList();
            tx.executeWithoutResult(status -> {
                TrackerSettings s = settings.load();
                Mapper mapper = new Mapper(s, s.jira.epics);
                for (IssueSnapshot snap : snaps) {
                    Issue parent = snap.parentKey() != null && !mapper.epics.contains(snap.parentKey())
                            ? issues.findByKey(snap.parentKey()).orElse(null) : null;
                    issues.save(mapper.issue(snap, parent));
                }
            });
        } finally {
            running.unlock();
        }
    }

    /** Syncs read Jira for seconds; inside a transaction they would hold the database's write lock that long. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Sync must not run inside a transaction");
        }
    }

    private Fetched fetch(List<String> epics, @Nullable Instant since) {
        if (epics.isEmpty()) {
            return new Fetched(List.of(), List.of());
        }
        List<EpicSnapshot> found = epics.stream().map(reader::findEpic).flatMap(Optional::stream).toList();
        return new Fetched(found, reader.search(epics, since));
    }

    private int apply(List<String> epics, Fetched fetched, boolean full) {
        if (epics.isEmpty()) {
            return 0;
        }
        fetched.epics().forEach(e -> anken.save(new Anken(e.key(), e.project(), e.summary(), e.dueDate())));
        anken.deleteAllExcept(epics);
        List<IssueSnapshot> snaps = fetched.issues();
        if (full && snaps.isEmpty() && !issues.findByEpics(epics).isEmpty()) {
            throw new EmptySyncException("Jira returned no issues for the tracked epics; kept the existing data "
                    + "(check the service account's access)");
        }
        TrackerSettings s = settings.load();
        snaps.forEach(snap -> s.statusMapping.seen.put(snap.statusName(), snap.statusCategory()));
        Mapper mapper = new Mapper(s, epics);
        Map<String, Issue> parents = new LinkedHashMap<>();
        issues.findByEpics(epics).stream().filter(i -> i.kind() == IssueKind.PARENT).forEach(i -> parents.put(i.key(), i));
        List<IssueSnapshot> ordered = new ArrayList<>(snaps); // parents first, so sub-tasks find their phase
        ordered.sort(Comparator.comparing((IssueSnapshot snap) -> !mapper.isPhaseParent(snap)));
        for (IssueSnapshot snap : ordered) {
            Issue i = mapper.issue(snap, snap.parentKey() == null ? null : parents.get(snap.parentKey()));
            if (i.kind() == IssueKind.PARENT) {
                parents.put(i.key(), i);
            }
            issues.save(i);
        }
        if (full) {
            Set<String> seen = new HashSet<>(snaps.stream().map(IssueSnapshot::key).toList());
            issues.deleteAll(issues.findAll().stream().map(Issue::key).filter(k -> !seen.contains(k)).toList());
        }
        settings.save(s);
        return snaps.size();
    }

    /** Effective status of a Jira status: the admin's mapping, else its category. */
    public static Status status(TrackerSettings s, @Nullable String name, @Nullable String category) {
        String key = name == null ? "" : name;
        String mapped = s.statusMapping.byName.get(key);
        if (Text.hasText(mapped)) {
            return Status.of(mapped);
        }
        String cat = Text.hasText(category) ? category : s.statusMapping.seen.get(key);
        return CATEGORY_STATUS.getOrDefault(cat == null ? "" : cat, Status.WIP);
    }

    /** Turns snapshots into domain issues for one sync, creating members for accounts not seen before. */
    private final class Mapper {
        final TrackerSettings s;
        final Set<String> epics;
        final Naming.CompiledRule rule;
        final Map<String, Member> byAccount = new HashMap<>();
        final Set<String> ids = new HashSet<>();

        Mapper(TrackerSettings settings, List<String> epics) {
            this.s = settings;
            this.epics = new HashSet<>(epics);
            this.rule = Naming.compileRule(settings.nameRules.rule, settings.nameRules.aliases);
            for (Member m : members.findAll()) {
                if (Text.hasText(m.jiraAccountId())) {
                    byAccount.put(m.jiraAccountId(), m);
                }
                ids.add(m.id());
            }
        }

        boolean isPhaseParent(IssueSnapshot snap) {
            return snap.parentKey() != null && epics.contains(snap.parentKey());
        }

        @Nullable String member(@Nullable UserSnapshot user) {
            return user != null ? memberOf(user) : null;
        }

        String memberOf(UserSnapshot user) {
            Member m = byAccount.get(user.accountId());
            if (m == null) {
                Member linked = Text.hasText(user.email()) ? members.findByEmail(user.email()).orElse(null) : null;
                if (linked != null && linked.jiraAccountId() == null) {
                    m = new Member(linked.id(), linked.name(), linked.fullName(), linked.role(), linked.country(), linked.color(),
                            linked.email(), linked.permission(), linked.external(), user.accountId());
                } else {
                    String id = MemberIds.newId(MemberIds.slugify(user.displayName()), ids);
                    m = new Member(id, user.displayName(), user.displayName(), null, s.defaultCountry, MemberIds.color(id), user.email(),
                            Permission.USER, false, user.accountId());
                }
                members.save(m);
                byAccount.put(user.accountId(), m);
                ids.add(m.id());
            }
            return m.id();
        }

        @Nullable String statusValue(@Nullable String name) {
            return Text.hasText(name) ? status(s, name, null).value() : null;
        }

        Issue issue(IssueSnapshot snap, @Nullable Issue parent) {
            boolean isParent = isPhaseParent(snap);
            String functionId = null;
            String functionName = null;
            StepKind step = null;
            // Sub-tasks of a Task whose template turns the workflow off are never function steps.
            if (!isParent && (parent == null || s.structure.hasWorkflow(parent.summary()))) {
                Naming.Parsed p = Naming.parseSummary(rule, snap.summary());
                String key = p != null ? Naming.functionKey(p) : null;
                if (key != null) {
                    functionId = key;
                    functionName = p.values().get("function_name");
                    step = p.step();
                }
            }
            String assigneeId = member(snap.assignee());
            List<Worklog> worklogs = snap.worklogs().stream()
                    .map((WorklogSnapshot w) -> new Worklog(w.date(), memberOf(w.author()), w.hours(), w.comment())).toList();
            List<IssueEvent> events = new ArrayList<>();
            for (ChangeSnapshot c : snap.changes()) {
                if (c.field().equals("assignee")) {
                    events.add(new IssueEvent(c.at(), EventField.ASSIGNEE, member(c.fromUser()), member(c.toUser())));
                } else if (c.field().equals("status")) {
                    events.add(new IssueEvent(c.at(), EventField.STATUS, statusValue(c.fromValue()), statusValue(c.toValue())));
                }
            }
            // the first reporter: the one before the first reporter change, else the current one
            String reporterId = snap.changes().stream().filter(c -> c.field().equals("reporter")).findFirst()
                    .map(c -> c.fromUser() != null ? member(c.fromUser()) : member(c.toUser())).orElseGet(() -> member(snap.reporter()));
            TrackerSettings.FieldMapping fm = s.fieldMapping;
            Double hours = JiraValues.hours(snap.fields().get(fm.effort), fm.effort);
            Double remaining = JiraValues.hours(snap.fields().get(fm.remaining), fm.remaining);
            return new Issue(
                    snap.key(), snap.project(),
                    isParent ? snap.parentKey() : (parent != null ? parent.epicKey() : ""),
                    snap.parentKey(), isParent ? IssueKind.PARENT : IssueKind.SUBTASK, snap.summary(),
                    isParent ? s.structure.phaseOfTask(snap.summary()) : (parent != null ? parent.phase() : ""),
                    status(s, snap.statusName(), snap.statusCategory()), assigneeId,
                    JiraValues.date(snap.fields().get(fm.plannedStart)), JiraValues.date(snap.fields().get(fm.plannedEnd)),
                    hours != null ? hours : 0,
                    remaining != null ? remaining : hours != null ? hours : 0, snap.created(), snap.statusName(), functionId, functionName, step, worklogs, events, reporterId);
        }
    }
}
