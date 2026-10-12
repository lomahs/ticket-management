package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tracker.adapters.persistence.Database;
import tracker.application.SyncFromJira.SyncResult;
import tracker.application.dto.ChangeSnapshot;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.UserSnapshot;
import tracker.application.dto.WorklogSnapshot;
import tracker.application.ports.Clock;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraReader;
import tracker.application.ports.UnitOfWork;
import tracker.application.settings.TrackerSettings;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.Member;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;

class SyncTest {
    static final UserSnapshot AN = new UserSnapshot("acc-an", "Nguyễn Thị An", "an@acme.vn");
    static final UserSnapshot MINH = new UserSnapshot("acc-minh", "Hoàng Minh");
    static final UserSnapshot NEW = new UserSnapshot("acc-new", "Lê Văn Mới");
    static final EpicSnapshot EPIC = new EpicSnapshot("PAY-1100", "PAY", "Payment flow revision", "2026-11-20");
    static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    static final Clock CLOCK = new Clock() {
        @Override
        public String today() {
            return "2026-10-07";
        }

        @Override
        public Instant now() {
            return NOW;
        }
    };

    static IssueSnapshot snap(String key, String summary, String parent, String status, String cat, UserSnapshot assignee,
                              Map<String, Object> fields, List<WorklogSnapshot> worklogs, List<ChangeSnapshot> changes) {
        return new IssueSnapshot(key, "PAY", summary, status, cat, assignee, parent, "2026-09-01", "2026-10-06T10:00:00+00:00",
                fields, worklogs, changes);
    }

    static IssueSnapshot snap(String key, String summary, String parent) {
        return snap(key, summary, parent, "In Progress", "indeterminate", AN, Map.of(), List.of(), List.of());
    }

    static final IssueSnapshot PARENT = snap("PAY-1102", "[Implement_Coding] Payment flow revision", "PAY-1100");
    static final IssueSnapshot CREATE = snap("PAY-1201", "[Implement_Coding][F01] Payment method selection – Create", "PAY-1102",
            "In Progress", "indeterminate", AN,
            Map.of("customfield_10020", "2026-09-14", "customfield_10016", "2026-09-15T00:00:00.000+0700", "timeoriginalestimate", 36000),
            List.of(new WorklogSnapshot(AN, "2026-09-15", 2.5, "spec")), List.of());
    static final IssueSnapshot REVIEW = snap("PAY-1202", "[Implement_Coding][F01] Payment method selection – BAL Review", "PAY-1102",
            "Resolved", "done", MINH, Map.of(), List.of(),
            List.of(new ChangeSnapshot("2026-09-16", "assignee", "acc-an", "acc-minh", AN, MINH),
                    new ChangeSnapshot("2026-09-17", "status", "In Progress", "Resolved")));
    static final IssueSnapshot OTHER = snap("PAY-1180", "Prepare Q&A list for JM", "PAY-1102", "To Do", "new", NEW, Map.of(), List.of(),
            List.of());

    /** A reader serving fixed snapshots, optionally failing, recording its search calls. */
    static class StubReader implements JiraReader {
        List<IssueSnapshot> issues;
        final Map<String, EpicSnapshot> epics;
        final RuntimeException fail;
        final List<Instant> since = Collections.synchronizedList(new ArrayList<>());

        StubReader(List<IssueSnapshot> issues, Map<String, EpicSnapshot> epics, RuntimeException fail) {
            this.issues = issues;
            this.epics = epics;
            this.fail = fail;
        }

        StubReader(List<IssueSnapshot> issues) {
            this(issues, Map.of("PAY-1100", EPIC), null);
        }

        @Override
        public Iterable<IssueSnapshot> search(List<String> epicKeys, Instant updatedSince) {
            since.add(updatedSince);
            if (fail != null) {
                throw fail;
            }
            return new ArrayList<>(issues);
        }

        @Override
        public EpicSnapshot getEpic(String key) {
            return epics.get(key);
        }

        @Override
        public IssueSnapshot getIssue(String key) {
            return issues.stream().filter(i -> i.key().equals(key)).findFirst().orElse(null);
        }

        @Override
        public List<JiraField> listFields() {
            return List.of();
        }

        @Override
        public boolean testConnection() {
            return fail == null;
        }
    }

    @TempDir
    Path tmp;
    Database store;

    @BeforeEach
    void setUp() {
        store = new Database("sqlite:///" + tmp.resolve("t.db"));
        store.migrate();
        try (UnitOfWork u = store.begin()) {
            u.members().save(new Member("an", "An", "Nguyễn Thị An", Role.BA, "VN", "#111", "", tracker.domain.Permission.USER, false, "acc-an"));
            TrackerSettings s = u.settings().load();
            s.jira.epics = new ArrayList<>(List.of("PAY-1100"));
            s.statusMapping.byName = new LinkedHashMap<>(Map.of("Resolved", "R"));
            u.settings().save(s);
            u.commit();
        }
    }

    SyncResult run(JiraReader reader, boolean full) {
        return new SyncFromJira(store, reader, CLOCK).run(full);
    }

    Map<String, Issue> issues() {
        try (UnitOfWork u = store.begin()) {
            Map<String, Issue> out = new HashMap<>();
            u.issues().list().forEach(i -> out.put(i.key(), i));
            return out;
        }
    }

    @Test
    void fullSyncMapsSnapshotsToDomain() {
        SyncResult r = run(new StubReader(List.of(PARENT, CREATE, REVIEW, OTHER)), true);
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.issues()).isEqualTo(4);
        Map<String, Issue> got = issues();
        Issue p = got.get("PAY-1102"), c = got.get("PAY-1201"), rv = got.get("PAY-1202"), o = got.get("PAY-1180");
        assertThat(p.kind()).isEqualTo(IssueKind.PARENT);
        assertThat(p.phase()).isEqualTo("Implement_Coding");
        assertThat(p.epicKey()).isEqualTo("PAY-1100");
        assertThat(List.of(c.kind(), c.epicKey(), c.phase(), c.functionId(), c.functionName(), c.step())).containsExactly(
                IssueKind.SUBTASK, "PAY-1100", "Implement_Coding", "F01", "Payment method selection", StepKind.CREATE);
        assertThat(List.of(c.plannedStart(), c.plannedEnd(), c.estimateH(), c.status())).containsExactly(
                "2026-09-14", "2026-09-15", 10.0, Status.WIP);
        assertThat(c.assigneeId()).isEqualTo("an");
        assertThat(c.worklogs().get(0).memberId()).isEqualTo("an");
        assertThat(c.worklogs().get(0).hours()).isEqualTo(2.5);
        assertThat(rv.status()).isEqualTo(Status.RESOLVED); // by-name mapping wins over the "done" category
        assertThat(rv.events()).extracting(e -> e.field() + ":" + e.toValue())
                .containsExactly(EventField.ASSIGNEE + ":" + rv.assigneeId(), EventField.STATUS + ":R");
        assertThat(o.functionId()).isNull();
        assertThat(o.status()).isEqualTo(Status.NYS);
        assertThat(o.phase()).isEqualTo("Implement_Coding");
    }

    @Test
    void unknownAccountBecomesMemberWithoutRole() {
        run(new StubReader(List.of(PARENT, OTHER)), true);
        try (UnitOfWork u = store.begin()) {
            Member m = u.members().byAccount("acc-new");
            assertThat(List.of(m.id(), m.name(), m.country())).containsExactly("le-van-moi", "Lê Văn Mới", "VN");
            assertThat(m.role()).isNull();
            assertThat(u.members().byAccount("acc-minh")).isNull(); // only accounts that appear on synced issues are created
        }
    }

    @Test
    void epicBecomesAnkenAndStatusesAreRecorded() {
        run(new StubReader(List.of(PARENT, CREATE, REVIEW)), true);
        try (UnitOfWork u = store.begin()) {
            assertThat(u.anken().list()).containsExactly(new tracker.domain.Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"));
            assertThat(u.settings().load().statusMapping.seen).containsExactly(Map.entry("In Progress", "indeterminate"),
                    Map.entry("Resolved", "done"));
        }
    }

    @Test
    void failedReadKeepsExistingDataAndRecordsError() {
        run(new StubReader(List.of(PARENT, CREATE)), true);
        SyncResult r = run(new StubReader(List.of(), Map.of("PAY-1100", EPIC), new JiraAuthError("401 Unauthorized")), true);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).isEqualTo("JiraAuthError: 401 Unauthorized");
        assertThat(issues()).containsOnlyKeys("PAY-1102", "PAY-1201");
    }

    @Test
    void fullSyncRemovesIssuesNoLongerInJiraButIncrementalKeepsThem() {
        run(new StubReader(List.of(PARENT, CREATE, OTHER)), true);
        run(new StubReader(List.of(PARENT, CREATE)), false);
        assertThat(issues()).containsKey("PAY-1180");
        run(new StubReader(List.of(PARENT, CREATE)), true);
        assertThat(issues()).doesNotContainKey("PAY-1180");
    }

    @Test
    void incrementalSyncAsksForChangesSinceLastSuccessMinusMargin() {
        run(new StubReader(List.of(PARENT)), true);
        StubReader reader = new StubReader(List.of(CREATE));
        run(reader, false);
        assertThat(reader.since.get(0)).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(issues().get("PAY-1201").phase()).isEqualTo("Implement_Coding"); // parent came from the earlier sync
    }

    @Test
    void noTrackedEpicsIsASuccessfulEmptySync() {
        Database empty = new Database("sqlite:///" + tmp.resolve("empty.db"));
        empty.migrate();
        assertThat(new SyncFromJira(empty, new StubReader(List.of()), CLOCK).run(true).status()).isEqualTo("ok");
    }

    @Test
    void refreshResyncsOnlyTheGivenKeys() {
        StubReader reader = new StubReader(List.of(PARENT, CREATE, REVIEW));
        run(reader, true);
        reader.issues = List.of(PARENT, snap("PAY-1201", CREATE.summary(), "PAY-1102", "Closed", "done", AN, Map.of(), List.of(), List.of()),
                REVIEW);
        SyncResult r = new SyncFromJira(store, reader, CLOCK).refresh(List.of("PAY-1201", "PAY-9999"));
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.issues()).isEqualTo(1);
        Issue got = issues().get("PAY-1201");
        assertThat(got.status()).isEqualTo(Status.CLOSED);
        assertThat(got.phase()).isEqualTo("Implement_Coding");
    }

    @Test
    void concurrentRunsDoNotOverlap() throws InterruptedException {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        StubReader slow = new StubReader(List.of(PARENT)) {
            @Override
            public Iterable<IssueSnapshot> search(List<String> epicKeys, Instant updatedSince) {
                order.add("start");
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("end");
                return List.of(PARENT);
            }
        };
        SyncFromJira sync = new SyncFromJira(store, slow, CLOCK);
        Thread a = new Thread(() -> sync.run(true));
        Thread b = new Thread(() -> sync.run(true));
        a.start();
        b.start();
        a.join();
        b.join();
        assertThat(order).containsExactly("start", "end", "start", "end");
    }

    @Test
    void fullSyncThatSeesNothingKeepsExistingData() {
        run(new StubReader(List.of(PARENT, CREATE)), true);
        SyncResult r = run(new StubReader(List.of()), true);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(r.error()).contains("no issues");
        assertThat(issues()).containsOnlyKeys("PAY-1102", "PAY-1201");
    }

    @Test
    void slugsAndColours() {
        assertThat(SyncFromJira.slugify("Lê Văn Mới")).isEqualTo("le-van-moi");
        assertThat(SyncFromJira.slugify("Đặng Đức")).isEqualTo("dang-duc");
        assertThat(SyncFromJira.slugify("!!!")).isEqualTo("member");
        // the same colours zlib.crc32 picked in the original backend
        assertThat(SyncFromJira.memberColor("le-van-moi")).isEqualTo("oklch(0.56 0.14 35)");
        assertThat(SyncFromJira.memberColor("an")).isEqualTo("oklch(0.55 0.15 0)");
        assertThat(SyncFromJira.memberColor("tanaka")).isEqualTo("oklch(0.5 0.1 210)");
        assertThat(SyncFromJira.memberColor("x-2")).isEqualTo("oklch(0.48 0.04 260)");
    }
}
