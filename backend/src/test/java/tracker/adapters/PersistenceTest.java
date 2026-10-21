package tracker.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import tracker.TrackerTest;
import tracker.adapters.crypto.CryptoSecretBox;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.MemberMergeRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SettingsRepository;
import tracker.application.ports.SyncRunRepository.SyncRun;
import tracker.application.ports.SyncRunRepository;
import tracker.application.settings.SettingsStore;
import tracker.application.settings.TrackerSettings;
import tracker.domain.AccessRequest;
import tracker.domain.AccessStatus;
import tracker.domain.Anken;
import tracker.domain.Allocation;
import tracker.domain.AnkenMember;
import tracker.domain.AnkenScopeSetting;
import tracker.domain.EventField;
import tracker.domain.Feature;
import tracker.domain.Holiday;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.IssueKind;
import tracker.domain.Leave;
import tracker.domain.Level;
import tracker.domain.LeaveType;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.RequestStatus;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.TaskTemplate;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;
import tracker.domain.WorkStructure;

/** The JDBC repositories on a migrated SQLite database, and how transactions behave on it. */
@TrackerTest
class PersistenceTest {
    static final Instant AT = Instant.parse("2026-10-07T09:00:00.123456Z");

    @Autowired
    MemberRepository members;
    @Autowired
    MemberMergeRepository merger;
    @Autowired
    AccessRepository access;
    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    AllocationRepository allocations;
    @Autowired
    IssueRepository issues;
    @Autowired
    AnkenRepository anken;
    @Autowired
    CalendarRepository calendar;
    @Autowired
    SettingsRepository settings;
    @Autowired
    SettingsStore store;
    @Autowired
    SyncRunRepository runs;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;

    static Issue issue(List<Worklog> logs) {
        return new Issue("PAY-1201", "PAY", "PAY-1100", "PAY-1102", IssueKind.SUBTASK, "[Implement_Coding][F01] X – Create",
                "Implement_Coding", Status.WIP, "an", "2026-09-14", "2026-09-15", 10, 4, "2026-09-01", "In Progress", "F01", "X",
                StepKind.CREATE, logs, List.of(new IssueEvent("2026-09-14", EventField.ASSIGNEE, null, "an")), "minh");
    }

    @Test
    void flywayAppliedTheSchema() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success = 1").query(String.class).list()).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void memberByEmailIgnoresCase() {
        members.save(new Member("an", "An", "Nguyễn Thị An", Role.BA, "VN", "#111", "An.Nguyen@acme.vn", Permission.USER, false, "acc-an"));
        members.save(new Member("x", "X", "X", null, "JP", "#222"));
        assertThat(members.findByEmail("an.nguyen@ACME.VN")).map(Member::id).contains("an");
        assertThat(members.findByEmail("")).isEmpty();
        members.save(new Member("a2", "A2", "A2", null, "JP", "#333", "AN.nguyen@acme.vn", Permission.USER, false, null));
        assertThat(members.findAllByEmail("AN.NGUYEN@acme.vn")).extracting(Member::id).containsExactly("a2", "an");
        assertThat(members.findAllByEmail("")).isEmpty();
    }

    @Test
    void accessStatusAndOverridesRoundTrip() {
        members.save(new Member("an", "An", "An", Role.BA, "VN", "#111"));
        access.saveStatus("an", AccessStatus.ACTIVE);
        assertThat(access.findStatus("an")).contains(AccessStatus.ACTIVE);
        assertThat(access.findStatuses()).isEqualTo(Map.of("an", AccessStatus.ACTIVE));
        access.saveOverride("an", Feature.PLANNING, Level.WRITE);
        assertThat(access.findOverrides("an")).isEqualTo(Map.of(Feature.PLANNING, Level.WRITE));
        assertThat(access.findOverrides()).isEqualTo(Map.of("an", Map.of(Feature.PLANNING, Level.WRITE)));
        access.saveOverride("an", Feature.PLANNING, null);
        assertThat(access.findOverrides("an")).isEmpty();
    }

    @Test
    void ankenMembersRoundTrip() {
        members.save(new Member("an", "An", "An", Role.BA, "VN", "#111"));
        members.save(new Member("bo", "Bo", "Bo", Role.BA, "VN", "#222"));
        assertThat(ankenMembers.isEmpty()).isTrue();
        ankenMembers.add("PAY-1", "bo", "minh", "2026-10-07T09:00:00Z");
        ankenMembers.add("PAY-1", "an", "minh", "2026-10-07T09:00:00Z");
        ankenMembers.add("PAY-1", "an", "minh", "2026-10-08T09:00:00Z");
        assertThat(ankenMembers.findByEpic("PAY-1")).containsExactly(new AnkenMember("PAY-1", "an", "2026-10-07T09:00:00Z", "minh"),
                new AnkenMember("PAY-1", "bo", "2026-10-07T09:00:00Z", "minh"));
        assertThat(ankenMembers.findEpicsOf("an")).containsExactly("PAY-1");
        assertThat(ankenMembers.findAll()).isEqualTo(Map.of("PAY-1", java.util.Set.of("an", "bo")));
        assertThat(ankenMembers.exists("PAY-1", "an")).isTrue();
        ankenMembers.remove("PAY-1", "an");
        ankenMembers.remove("PAY-1", "bo");
        assertThat(ankenMembers.exists("PAY-1", "an")).isFalse();
        assertThat(ankenMembers.isEmpty()).isTrue();
    }

    @Test
    void ankenScopeRoundTrip() {
        members.save(new Member("an", "An", "An", Role.BA, "VN", "#111"));
        assertThat(access.findAnkenScope("an")).isEqualTo(AnkenScopeSetting.DEFAULT);
        access.saveAnkenScope("an", AnkenScopeSetting.ALL);
        assertThat(access.findAnkenScope("an")).isEqualTo(AnkenScopeSetting.ALL);
        assertThat(access.findAnkenScopes()).isEqualTo(Map.of("an", AnkenScopeSetting.ALL));
        access.saveAnkenScope("an", AnkenScopeSetting.DEFAULT);
        assertThat(access.findAnkenScopes()).isEmpty();
    }

    @Test
    void accessRequestsLifecycle() {
        AccessRequest r = access.addRequest("new@acme.vn", "New", "please", "2026-10-07T09:00:00Z", null);
        assertThat(r.id()).isPositive();
        assertThat(r.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(access.findRequests(RequestStatus.PENDING)).extracting(AccessRequest::id).containsExactly(r.id());
        access.decide(r.id(), RequestStatus.APPROVED, "minh", "2026-10-08T09:00:00Z", "an");
        AccessRequest d = access.findRequest(r.id()).orElseThrow();
        assertThat(d.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(d.decidedBy()).isEqualTo("minh");
        assertThat(d.memberId()).isEqualTo("an");
        assertThat(access.findRequests(RequestStatus.PENDING)).isEmpty();
        assertThat(access.findRequests(null)).extracting(AccessRequest::id).containsExactly(r.id());
        assertThat(access.findLatestRequest("new@acme.vn")).contains(d);
    }

    @Test
    void membersRoundTrip() {
        Member m = new Member("an", "An", "Nguyễn Thị An", Role.BA, "VN", "#111", "an@acme.vn", Permission.ADMIN, false, "acc-an");
        members.save(m);
        members.save(new Member("x", "X", "X", null, "JP", "#222"));
        assertThat(members.findById("an")).contains(m);
        assertThat(members.findByJiraAccountId("acc-an")).contains(m);
        assertThat(members.findById("x").orElseThrow().role()).isNull();
        assertThat(members.findAll()).extracting(Member::id).containsExactly("an", "x");
        members.save(new Member("an", "Ann", "Nguyễn Thị An", Role.BAL, "JP", "#111", "an@acme.vn", Permission.USER, false, "acc-an"));
        assertThat(members.findById("an").orElseThrow().role()).isEqualTo(Role.BAL);
    }

    @Test
    void issueRoundTripReplacesChildren() {
        Issue i = issue(List.of(new Worklog("2026-09-15", "an", 2.5, "spec")));
        issues.save(i);
        Issue got = issues.findByKey("PAY-1201").orElseThrow();
        assertThat(got).isEqualTo(i);
        issues.save(got.withWorklogs(List.of(new Worklog("2026-09-16", "an", 1, ""))));
        assertThat(issues.findByKey("PAY-1201").orElseThrow().worklogs()).extracting(Worklog::hours).containsExactly(1.0);
        assertThat(issues.findByEpics(List.of("PAY-1100"))).extracting(Issue::key).containsExactly("PAY-1201");
        assertThat(issues.findByEpics(List.of("X"))).isEmpty();
        issues.deleteAll(List.of("PAY-1201"));
        assertThat(issues.findByKey("PAY-1201")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM worklogs").query(Integer.class).single()).isZero(); // ON DELETE CASCADE
    }

    @Test
    void aFailedTransactionIsRolledBack() {
        tx.executeWithoutResult(status -> {
            issues.save(issue(List.of()));
            status.setRollbackOnly();
        });
        assertThat(issues.findByKey("PAY-1201")).isEmpty();
    }

    /**
     * A write transaction holds the write lock from its start, so another one waits for it instead of failing or losing
     * an update.
     */
    @Test
    void writeTransactionsRunOneAfterTheOther() throws Exception {
        CompletableFuture<?>[] other = new CompletableFuture<?>[1];
        tx.executeWithoutResult(status -> {
            TrackerSettings s = settings.load();
            other[0] = CompletableFuture.runAsync(() -> store.update(x -> x.defaultCountry = "JP"));
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(other[0]).isNotDone();
            s.jira.interval = "60";
            settings.save(s);
        });
        other[0].get(5, TimeUnit.SECONDS);
        TrackerSettings saved = settings.load();
        assertThat(List.of(saved.jira.interval, saved.defaultCountry)).containsExactly("60", "JP");
    }

    /** A long write (a sync) does not block readers; they see the last committed data. */
    @Test
    void readersAreNotBlockedByAnOpenWrite() {
        tx.executeWithoutResult(status -> {
            anken.save(new Anken("PAY-1", "PAY", "x", null));
            List<Anken> seen = CompletableFuture.supplyAsync(anken::findAll).orTimeout(2, TimeUnit.SECONDS).join();
            assertThat(seen).isEmpty();
        });
        assertThat(anken.findAll()).hasSize(1);
    }

    @Test
    void calendarRoundTrip() {
        Leave lv = calendar.saveLeave(new Leave(0, "an", "2026-10-16", LeaveType.FULL, "trip"));
        calendar.saveHoliday(new Holiday("2026-09-02", List.of("VN"), "National Day"));
        calendar.saveExtraDay("2026-10-10", "Release");
        assertThat(lv.id()).isPositive();
        assertThat(calendar.findLeaves()).containsExactly(lv);
        assertThat(calendar.findHolidays()).isEqualTo(Map.of("2026-09-02", new Holiday("2026-09-02", List.of("VN"), "National Day")));
        assertThat(calendar.findExtraDays()).isEqualTo(Map.of("2026-10-10", "Release"));
        calendar.deleteLeave(lv.id());
        calendar.deleteHoliday("2026-09-02");
        calendar.deleteExtraDay("2026-10-10");
        assertThat(calendar.findLeaves()).isEmpty();
        assertThat(calendar.findHolidays()).isEmpty();
        assertThat(calendar.findExtraDays()).isEmpty();
    }

    @Test
    void allocationsRoundTripAndUpsert() {
        members.save(new Member("a", "A", "A", Role.BA, "VN", "#111"));
        allocations.save(new Allocation("a", "PAY-1100", "2026-10-01", 4), "minh", "2026-10-07T09:00:00Z");
        allocations.save(new Allocation("a", "PAY-1100", "2026-10-01", 6), "minh", "2026-10-08T09:00:00Z");
        allocations.save(new Allocation("a", "PAY-1100", "2026-11-02", 0), "minh", "2026-10-08T09:00:00Z");

        assertThat(allocations.findByMember("a")).containsExactly(new Allocation("a", "PAY-1100", "2026-10-01", 6),
                new Allocation("a", "PAY-1100", "2026-11-02", 0));
        assertThat(allocations.findAll()).hasSize(2);
        assertThat(jdbc.sql("SELECT set_at FROM allocations WHERE effective_from = '2026-10-01'").query(String.class).single())
                .isEqualTo("2026-10-08T09:00:00Z");
    }

    @Test
    void mergeMovesAllocations() {
        members.save(new Member("a", "A", "A", Role.BA, "VN", "#111"));
        members.save(new Member("b", "B", "B", Role.BA, "VN", "#222"));
        allocations.save(new Allocation("a", "X", "2026-10-01", 5), "minh", "t");
        allocations.save(new Allocation("b", "X", "2026-10-01", 2), "minh", "t");
        allocations.save(new Allocation("b", "Y", "2026-10-01", 3), "minh", "t");

        merger.merge("a", "b");

        assertThat(allocations.findByMember("a")).containsExactlyInAnyOrder(new Allocation("a", "X", "2026-10-01", 5),
                new Allocation("a", "Y", "2026-10-01", 3));
        assertThat(allocations.findByMember("b")).isEmpty();
        assertThat(allocations.findAll()).hasSize(2);
    }

    @Test
    void mergeResolvesLeaveConflictsInFavourOfTheKeptMember() {
        members.save(new Member("a", "A", "A", Role.BA, "VN", "#111"));
        members.save(new Member("b", "B", "B", Role.BA, "VN", "#222", "b@acme.vn", Permission.USER, false, "acc-b"));
        calendar.saveLeave(new Leave(0, "a", "2026-11-02", LeaveType.FULL, "a-leave"));
        calendar.saveLeave(new Leave(0, "b", "2026-11-02", LeaveType.FULL, "b-leave"));
        calendar.saveLeave(new Leave(0, "b", "2026-11-03", LeaveType.FULL, "b-extra"));
        anken.save(new Anken("PAY-1100", "PAY", "x", null));
        issues.save(new Issue("PAY-1201", "PAY", "PAY-1100", "PAY-1102", IssueKind.SUBTASK, "[Implement_Coding][F01] X – Create",
                "Implement_Coding", Status.WIP, "b", "2026-09-14", "2026-09-15", 10, 4, "2026-09-01", "In Progress", "F01", "X",
                StepKind.CREATE, List.of(new Worklog("2026-09-15", "b", 2.5, "spec")),
                List.of(new IssueEvent("2026-09-14", EventField.ASSIGNEE, "b", "b")), "b"));
        ankenMembers.add("PAY-1100", "b", "minh", "2026-10-07T09:00:00Z");
        access.saveStatus("b", AccessStatus.ACTIVE);
        access.saveOverride("b", Feature.PLANNING, Level.WRITE);

        merger.merge("a", "b");

        assertThat(calendar.findLeaves()).extracting(Leave::memberId, Leave::date, Leave::note)
                .containsExactlyInAnyOrder(org.assertj.core.api.Assertions.tuple("a", "2026-11-02", "a-leave"),
                        org.assertj.core.api.Assertions.tuple("a", "2026-11-03", "b-extra"));
        Issue got = issues.findByKey("PAY-1201").orElseThrow();
        assertThat(got.assigneeId()).isEqualTo("a");
        assertThat(got.reporterId()).isEqualTo("a");
        assertThat(got.worklogs()).extracting(Worklog::memberId).containsExactly("a");
        assertThat(got.events()).extracting(IssueEvent::fromValue, IssueEvent::toValue).containsExactly(org.assertj.core.api.Assertions.tuple("a", "a"));
        assertThat(ankenMembers.exists("PAY-1100", "a")).isTrue();
        assertThat(ankenMembers.findEpicsOf("b")).isEmpty();
        assertThat(access.findStatus("a")).contains(AccessStatus.ACTIVE);
        assertThat(access.findOverrides("b")).isEmpty();
        assertThat(members.findById("a").orElseThrow().jiraAccountId()).isEqualTo("acc-b");
        assertThat(members.findById("b")).isEmpty();
    }

    @Test
    void mergeCarriesTheEmailRoleAndAdminFlagWhenTheKeptMemberHasNone() {
        members.save(new Member("synced", "Lan", "Lan", null, "VN", "#111", "", Permission.USER, false, "acc-lan"));
        members.save(new Member("added", "Lan V", "Lan Vo", Role.PIM, "VN", "#222", "lan.vo@acme.vn", Permission.ADMIN, false, null));
        access.saveStatus("added", AccessStatus.ACTIVE);

        merger.merge("synced", "added");

        Member kept = members.findById("synced").orElseThrow();
        assertThat(kept.email()).isEqualTo("lan.vo@acme.vn");
        assertThat(kept.role()).isEqualTo(Role.PIM);
        assertThat(kept.permission()).isEqualTo(Permission.ADMIN);
        assertThat(kept.jiraAccountId()).isEqualTo("acc-lan");
        assertThat(kept.name()).isEqualTo("Lan");
        assertThat(access.findStatus("synced")).contains(AccessStatus.ACTIVE);
        assertThat(members.findByEmail("lan.vo@acme.vn")).map(Member::id).contains("synced");
        assertThat(members.findById("added")).isEmpty();
    }

    @Test
    void mergeKeepsTheKeptMembersOwnEmailAndRole() {
        members.save(new Member("a", "A", "A", Role.BA, "VN", "#111", "a@acme.vn", Permission.ADMIN, false, null));
        members.save(new Member("b", "B", "B", Role.PM, "VN", "#222", "b@acme.vn", Permission.USER, false, "acc-b"));

        merger.merge("a", "b");

        Member kept = members.findById("a").orElseThrow();
        assertThat(kept.email()).isEqualTo("a@acme.vn");
        assertThat(kept.role()).isEqualTo(Role.BA);
        assertThat(kept.permission()).isEqualTo(Permission.ADMIN);
        assertThat(kept.jiraAccountId()).isEqualTo("acc-b");
    }

    @Test
    void settingsAnkenAndRuns() {
        TrackerSettings s = settings.load();
        s.jira.epics = new ArrayList<>(List.of("PAY-1100"));
        s.nameRules.aliases.put(StepKind.CREATE, "Create, Tạo");
        s.workingHours = new WorkingHours(8, List.of(1, 2, 3, 4, 5, 6), 1);
        settings.save(s);
        anken.save(new Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"));
        int rid = runs.start(true, AT);
        runs.finish(rid, AT, "ok", 12, null);

        TrackerSettings got = settings.load();
        assertThat(got.jira.epics).containsExactly("PAY-1100");
        assertThat(got.nameRules.aliases.get(StepKind.CREATE)).isEqualTo("Create, Tạo");
        assertThat(got.workingHours.days()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(anken.findAll()).containsExactly(new Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"));
        SyncRun r = runs.findLatest().orElseThrow();
        assertThat(List.of(r.status(), r.issues(), r.full(), r.startedAt())).containsExactly("ok", 12, true, AT);
        assertThat(runs.findLatestOk()).contains(r);
    }

    @Test
    void structureRoundTripsAndDefaultsWhenMissing() {
        jdbc.sql("INSERT INTO settings (id, value) VALUES (1, '{\"default_country\":\"JP\"}')").update(); // written before the setting existed
        assertThat(settings.load().structure).isEqualTo(WorkStructure.empty());
        TrackerSettings s = settings.load();
        s.structure = new WorkStructure(List.of("Study", "FUT"), List.of(new TaskTemplate("FUT create test case", "FUT", "FUTTC"),
                new TaskTemplate("QnA management for *", "FUT", "", false)));
        settings.save(s);
        assertThat(settings.load().structure).isEqualTo(s.structure);
        assertThat(jdbc.sql("SELECT value FROM settings").query(String.class).single())
                .contains("\"structure\":{\"phases\":[\"Study\",\"FUT\"],\"tasks\":[{\"match\":\"FUT create test case\"");
    }

    @Test
    void missingThresholdDefaultsToOneHour() {
        // a document written before the effort threshold existed
        jdbc.sql("INSERT INTO settings (id, value) VALUES (1, :v)").param("v",
                "{\"thresholds\":{\"soon_days\":4,\"effort_warn_pct\":70,\"held_days\":3,\"review_rounds\":3,\"capacity_pct\":100}}").update();
        assertThat(settings.load().thresholds.effortDiffH()).isEqualTo(1.0);
        assertThat(settings.load().thresholds.soonDays()).isEqualTo(4);
    }

    @Test
    void settingsAreOneJsonDocumentWithSnakeCaseNames() {
        settings.save(new TrackerSettings());
        String json = jdbc.sql("SELECT value FROM settings").query(String.class).single();
        assertThat(json).contains("\"field_mapping\":{\"planned_start\":\"customfield_10020\"").contains("\"default_country\":\"VN\"")
                .contains("\"aliases\":{\"create\":\"Create\"");
    }

    @Test
    void secretBoxRoundTripAndKeyFile() throws Exception {
        Path dir = Files.createTempDirectory("keys");
        CryptoSecretBox box = CryptoSecretBox.fromKeyFile(dir.resolve("secret.key"));
        String token = box.encrypt("ATATT3xFfGF0-real-token");
        assertThat(token).isNotEqualTo("ATATT3xFfGF0-real-token").doesNotContain("ATATT");
        assertThat(CryptoSecretBox.fromKeyFile(dir.resolve("secret.key")).decrypt(token)).isEqualTo("ATATT3xFfGF0-real-token");
        assertThat(Files.readString(dir.resolve("secret.key"))).hasSize(64);
        assertThat(new CryptoSecretBox("other-key").encrypt("x")).isNotEqualTo(new CryptoSecretBox("other-key").encrypt("x")); // random IV
    }
}
