package tracker.application;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

import tracker.application.dto.ChangeSnapshot;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.UserSnapshot;
import tracker.application.dto.WorklogSnapshot;
import tracker.application.ports.Clock;
import tracker.application.ports.JiraReader;
import tracker.application.ports.Repositories.SyncRun;
import tracker.application.ports.UnitOfWork;
import tracker.application.ports.UnitOfWorkFactory;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.IssueKind;
import tracker.domain.Member;
import tracker.domain.Naming;
import tracker.domain.Permission;
import tracker.domain.Py;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Worklog;

/** SyncFromJira: reads the tracked epics from Jira and stores them as domain issues. */
public final class SyncFromJira {
    public static final Duration INCREMENTAL_MARGIN = Duration.ofMinutes(5);
    /** One sync at a time per process (scheduler, Sync button, settings changes). */
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();
    public static final Map<String, Status> CATEGORY_STATUS = Map.of(
            "new", Status.NYS, "indeterminate", Status.WIP, "done", Status.CLOSED);
    private static final Set<String> SECONDS_FIELDS = Set.of("timeoriginalestimate", "aggregatetimeoriginalestimate", "timeestimate");
    private static final List<String> MEMBER_COLORS = List.of(
            "oklch(0.55 0.13 255)", "oklch(0.52 0.13 305)", "oklch(0.56 0.14 35)", "oklch(0.52 0.11 150)",
            "oklch(0.5 0.1 210)", "oklch(0.48 0.04 260)", "oklch(0.55 0.15 0)", "oklch(0.5 0.12 100)");

    /** Jira returned nothing for epics that already have issues here. (Named as in the original backend.) */
    public static class EmptySyncError extends RuntimeException {
        public EmptySyncError(String message) {
            super(message);
        }
    }

    /** @param status ok | failed */
    public record SyncResult(int runId, String status, int issues, String error) {
    }

    private final UnitOfWorkFactory uowFactory;
    private final JiraReader reader;
    private final Clock clock;

    public SyncFromJira(UnitOfWorkFactory uowFactory, JiraReader reader, Clock clock) {
        this.uowFactory = uowFactory;
        this.reader = reader;
        this.clock = clock;
    }

    public static String slugify(String name) {
        String nfd = Normalizer.normalize(name, Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder();
        nfd.codePoints().filter(c -> Character.getType(c) != Character.NON_SPACING_MARK).forEach(folded::appendCodePoint);
        String s = folded.toString().replace("đ", "d").replace("Đ", "D").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "member" : s;
    }

    public static String memberColor(String memberId) {
        CRC32 crc = new CRC32();
        crc.update(memberId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return MEMBER_COLORS.get((int) (crc.getValue() % MEMBER_COLORS.size()));
    }

    private static String date(Object v) {
        if (!Py.truthy(v)) {
            return null;
        }
        String s = Py.str(v);
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    /** Turns snapshots into domain objects for one sync, creating members for unknown accounts. */
    private static final class Mapper {
        final UnitOfWork uow;
        final TrackerSettings s;
        final Set<String> epics;
        final Naming.CompiledRule rule;
        final Map<String, Member> members = new HashMap<>();
        final Set<String> ids = new HashSet<>();

        Mapper(UnitOfWork uow, TrackerSettings settings, List<String> epics) {
            this.uow = uow;
            this.s = settings;
            this.epics = new HashSet<>(epics);
            this.rule = Naming.compileRule(settings.nameRules.rule, settings.nameRules.aliases);
            for (Member m : uow.members().list()) {
                if (Py.truthy(m.jiraAccountId())) {
                    members.put(m.jiraAccountId(), m);
                }
                ids.add(m.id());
            }
        }

        String member(UserSnapshot user) {
            return user != null ? memberOf(user) : null;
        }

        String memberOf(UserSnapshot user) {
            Member m = members.get(user.accountId());
            if (m == null) {
                String base = slugify(user.displayName());
                String mid = base;
                int n = 2;
                while (ids.contains(mid)) {
                    mid = base + "-" + n;
                    n++;
                }
                m = new Member(mid, user.displayName(), user.displayName(), null, s.defaultCountry, memberColor(mid),
                        user.email(), Permission.USER, false, user.accountId());
                uow.members().save(m);
                members.put(user.accountId(), m);
                ids.add(mid);
            }
            return m.id();
        }

        Status status(String name, String category) {
            String mapped = s.statusMapping.byName.get(name == null ? "" : name);
            if (Py.truthy(mapped)) {
                return Status.of(mapped);
            }
            String cat = Py.truthy(category) ? category : s.statusMapping.seen.get(name == null ? "" : name);
            return CATEGORY_STATUS.getOrDefault(cat == null ? "" : cat, Status.WIP);
        }

        double hours(IssueSnapshot snap) {
            String fid = s.fieldMapping.effort;
            Object v = snap.fields().get(fid);
            if (v == null || "".equals(v)) {
                return 0;
            }
            return SECONDS_FIELDS.contains(fid) ? Py.toFloat(v) / 3600 : Py.toFloat(v);
        }

        Issue issue(IssueSnapshot snap, Issue parent) {
            String epic = snap.parentKey() != null && epics.contains(snap.parentKey()) ? snap.parentKey() : null; // phase parents
            boolean isParent = epic != null;
            TrackerSettings.FieldMapping fm = s.fieldMapping;
            String functionId = null;
            String functionName = null;
            StepKind step = null;
            if (!isParent) {
                Naming.Parsed p = Naming.parseSummary(rule, snap.summary());
                if (p != null && Py.truthy(p.values().get("function_id"))) {
                    functionId = p.values().get("function_id");
                    functionName = p.values().get("function_name");
                    step = p.step();
                }
            }
            Status status = status(snap.statusName(), snap.statusCategory());
            String assigneeId = member(snap.assignee()); // members are created in the order the original backend met them
            List<Worklog> worklogs = new ArrayList<>();
            for (WorklogSnapshot w : snap.worklogs()) {
                worklogs.add(new Worklog(w.date(), memberOf(w.author()), w.hours(), w.comment()));
            }
            List<IssueEvent> events = new ArrayList<>();
            for (ChangeSnapshot c : snap.changes()) {
                if (c.field().equals("assignee")) {
                    events.add(new IssueEvent(c.at(), EventField.ASSIGNEE, member(c.fromUser()), member(c.toUser())));
                } else if (c.field().equals("status")) {
                    events.add(new IssueEvent(c.at(), EventField.STATUS,
                            Py.truthy(c.fromValue()) ? status(c.fromValue(), null).value() : null,
                            Py.truthy(c.toValue()) ? status(c.toValue(), null).value() : null));
                }
            }
            return new Issue(
                    snap.key(), snap.project(),
                    epic != null ? epic : (parent != null ? parent.epicKey() : ""),
                    snap.parentKey(), isParent ? IssueKind.PARENT : IssueKind.SUBTASK,
                    snap.summary(),
                    isParent ? Naming.parsePhase(snap.summary()) : (parent != null ? parent.phase() : ""),
                    status, assigneeId,
                    date(snap.fields().get(fm.plannedStart)), date(snap.fields().get(fm.plannedEnd)),
                    hours(snap), snap.created(), snap.statusName(), functionId, functionName, step, worklogs, events);
        }
    }

    public SyncResult run(boolean full) {
        RUN_LOCK.lock();
        try {
            return doRun(full);
        } finally {
            RUN_LOCK.unlock();
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private SyncResult doRun(boolean full) {
        SyncRun lastOk;
        int runId;
        try (UnitOfWork uow = uowFactory.begin()) {
            lastOk = uow.syncRuns().latestOk();
            runId = uow.syncRuns().start(full, clock.now());
            uow.commit();
        }
        Instant since = full || lastOk == null ? null : lastOk.startedAt().minus(INCREMENTAL_MARGIN);
        try {
            int n;
            try (UnitOfWork uow = uowFactory.begin()) {
                n = sync(uow, since, full);
                uow.syncRuns().finish(runId, clock.now(), "ok", n, null);
                uow.commit();
            }
            return new SyncResult(runId, "ok", n, null);
        } catch (Exception e) { // any failure is recorded on the run; stored data stays as it was
            try (UnitOfWork uow = uowFactory.begin()) {
                uow.syncRuns().finish(runId, clock.now(), "failed", 0, describe(e));
                uow.commit();
            }
            return new SyncResult(runId, "failed", 0, describe(e));
        }
    }

    /** Re-reads specific issues after a write; keys Jira no longer has are skipped. */
    public SyncResult refresh(List<String> keys) {
        RUN_LOCK.lock();
        try (UnitOfWork uow = uowFactory.begin()) {
            TrackerSettings settings = uow.settings().load();
            Mapper mapper = new Mapper(uow, settings, settings.jira.epics);
            int n = 0;
            for (String key : keys) {
                IssueSnapshot snap = reader.getIssue(key);
                if (snap == null) {
                    continue;
                }
                Issue parent = snap.parentKey() != null && !mapper.epics.contains(snap.parentKey())
                        ? uow.issues().get(snap.parentKey()) : null;
                uow.issues().save(mapper.issue(snap, parent));
                n++;
            }
            uow.commit();
            return new SyncResult(0, "ok", n, null);
        } finally {
            RUN_LOCK.unlock();
        }
    }

    private int sync(UnitOfWork uow, Instant since, boolean full) {
        TrackerSettings settings = uow.settings().load();
        List<String> epics = new ArrayList<>(settings.jira.epics);
        if (epics.isEmpty()) {
            return 0;
        }
        for (String key : epics) {
            EpicSnapshot e = reader.getEpic(key);
            if (e != null) {
                uow.anken().save(new Anken(e.key(), e.project(), e.summary(), e.dueDate()));
            }
        }
        uow.anken().deleteExcept(epics);

        List<IssueSnapshot> snaps = new ArrayList<>();
        reader.search(epics, since).forEach(snaps::add);
        if (full && snaps.isEmpty() && !uow.issues().list(epics).isEmpty()) {
            throw new EmptySyncError("Jira returned no issues for the tracked epics; kept the existing data "
                    + "(check the service account's access)");
        }
        for (IssueSnapshot s : snaps) {
            settings.statusMapping.seen.put(s.statusName(), s.statusCategory());
        }
        Mapper mapper = new Mapper(uow, settings, epics);
        Map<String, Issue> parents = new LinkedHashMap<>();
        for (Issue i : uow.issues().list(epics)) {
            if (i.kind() == IssueKind.PARENT) {
                parents.put(i.key(), i);
            }
        }
        List<IssueSnapshot> ordered = new ArrayList<>(snaps); // parents first
        ordered.sort(Comparator.comparing((IssueSnapshot s) -> !(s.parentKey() != null && mapper.epics.contains(s.parentKey()))));
        for (IssueSnapshot s : ordered) {
            Issue i = mapper.issue(s, parents.get(s.parentKey() == null ? "" : s.parentKey()));
            if (i.kind() == IssueKind.PARENT) {
                parents.put(i.key(), i);
            }
            uow.issues().save(i);
        }
        if (full) {
            Set<String> seen = new HashSet<>();
            snaps.forEach(s -> seen.add(s.key()));
            uow.issues().delete(uow.issues().list().stream().map(Issue::key).filter(k -> !seen.contains(k)).toList());
        }
        uow.settings().save(settings);
        return snaps.size();
    }
}
