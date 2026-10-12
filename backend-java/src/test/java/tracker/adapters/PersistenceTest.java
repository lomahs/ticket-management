package tracker.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.adapters.persistence.Database;
import tracker.adapters.persistence.FernetSecretBox;
import tracker.application.ports.Repositories.SyncRun;
import tracker.application.ports.UnitOfWork;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.EventField;
import tracker.domain.Holiday;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.IssueKind;
import tracker.domain.Leave;
import tracker.domain.LeaveType;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;

class PersistenceTest {
    static final Instant AT = Instant.parse("2026-10-07T09:00:00Z");

    @TempDir
    Path tmp;
    Database db;

    @BeforeEach
    void setUp() {
        db = new Database("sqlite:///" + tmp.resolve("tracker.db"));
        db.migrate();
    }

    static Issue issue(List<Worklog> logs) {
        return new Issue("PAY-1201", "PAY", "PAY-1100", "PAY-1102", IssueKind.SUBTASK, "[Implement_Coding][F01] X – Create",
                "Implement_Coding", Status.WIP, "an", "2026-09-14", "2026-09-15", 10, "2026-09-01", "In Progress", "F01", "X",
                StepKind.CREATE, logs, List.of(new IssueEvent("2026-09-14", EventField.ASSIGNEE, null, "an")));
    }

    @Test
    void migrateIsIdempotent() {
        db.migrate();
    }

    @Test
    void membersRoundTrip() {
        Member m = new Member("an", "An", "Nguyễn Thị An", Role.BA, "VN", "#111", "an@acme.vn", Permission.ADMIN, false, "acc-an");
        try (UnitOfWork u = db.begin()) {
            u.members().save(m);
            u.members().save(new Member("x", "X", "X", null, "JP", "#222"));
            u.commit();
        }
        try (UnitOfWork u = db.begin()) {
            assertThat(u.members().get("an")).isEqualTo(m);
            assertThat(u.members().byAccount("acc-an")).isEqualTo(m);
            assertThat(u.members().get("x").role()).isNull();
            assertThat(u.members().list()).extracting(Member::id).containsExactly("an", "x");
        }
    }

    @Test
    void issueRoundTripReplacesChildren() {
        Issue i = issue(List.of(new Worklog("2026-09-15", "an", 2.5, "spec")));
        try (UnitOfWork u = db.begin()) {
            u.issues().save(i);
            u.commit();
        }
        try (UnitOfWork u = db.begin()) {
            Issue got = u.issues().get("PAY-1201");
            assertThat(got).isEqualTo(i);
            u.issues().save(got.withWorklogs(List.of(new Worklog("2026-09-16", "an", 1, ""))));
            u.commit();
        }
        try (UnitOfWork u = db.begin()) {
            assertThat(u.issues().get("PAY-1201").worklogs()).extracting(Worklog::hours).containsExactly(1.0);
            assertThat(u.issues().list(List.of("PAY-1100"))).extracting(Issue::key).containsExactly("PAY-1201");
            assertThat(u.issues().list(List.of("X"))).isEmpty();
            u.issues().delete(List.of("PAY-1201"));
            u.commit();
        }
        try (UnitOfWork u = db.begin()) {
            assertThat(u.issues().get("PAY-1201")).isNull();
        }
    }

    @Test
    void uncommittedWorkIsRolledBack() {
        try (UnitOfWork u = db.begin()) {
            u.issues().save(issue(List.of()));
        }
        try (UnitOfWork u = db.begin()) {
            assertThat(u.issues().get("PAY-1201")).isNull();
        }
    }

    /** Reads do not pin a snapshot: a unit of work can still write after another one committed (as with pysqlite). */
    @Test
    void writeAfterAnotherCommitSucceeds() {
        try (UnitOfWork a = db.begin()) {
            TrackerSettings s = a.settings().load();
            try (UnitOfWork b = db.begin()) {
                TrackerSettings other = b.settings().load();
                other.defaultCountry = "JP";
                b.settings().save(other);
                b.commit();
            }
            s.jira.interval = "60";
            a.settings().save(s);
            a.commit();
        }
        try (UnitOfWork u = db.begin()) {
            assertThat(u.settings().load().jira.interval).isEqualTo("60");
        }
    }

    /** A long write (a sync) does not block readers. */
    @Test
    void readersAreNotBlockedByAnOpenWrite() {
        try (UnitOfWork w = db.begin()) {
            w.anken().save(new Anken("PAY-1", "PAY", "x", null));
            try (UnitOfWork r = db.begin()) {
                assertThat(r.anken().list()).isEmpty();
            }
            w.commit();
        }
    }

    @Test
    void calendarRoundTrip() {
        Leave lv;
        try (UnitOfWork u = db.begin()) {
            lv = u.calendar().addLeave(new Leave(0, "an", "2026-10-16", LeaveType.FULL, "trip"));
            u.calendar().saveHoliday(new Holiday("2026-09-02", List.of("VN"), "National Day"));
            u.calendar().saveExtraDay("2026-10-10", "Release");
            u.commit();
        }
        assertThat(lv.id()).isPositive();
        try (UnitOfWork u = db.begin()) {
            assertThat(u.calendar().leaves()).containsExactly(lv);
            assertThat(u.calendar().holidays()).isEqualTo(Map.of("2026-09-02", new Holiday("2026-09-02", List.of("VN"), "National Day")));
            assertThat(u.calendar().extraDays()).isEqualTo(Map.of("2026-10-10", "Release"));
            u.calendar().deleteLeave(lv.id());
            u.calendar().deleteHoliday("2026-09-02");
            u.calendar().deleteExtraDay("2026-10-10");
            u.commit();
        }
        try (UnitOfWork u = db.begin()) {
            assertThat(u.calendar().leaves()).isEmpty();
            assertThat(u.calendar().holidays()).isEmpty();
            assertThat(u.calendar().extraDays()).isEmpty();
        }
    }

    @Test
    void settingsAnkenAndRuns() {
        try (UnitOfWork u = db.begin()) {
            TrackerSettings s = u.settings().load();
            s.jira.epics = new ArrayList<>(List.of("PAY-1100"));
            s.nameRules.aliases.put(StepKind.CREATE, "Create, Tạo");
            s.workingHours = new WorkingHours(8, List.of(1, 2, 3, 4, 5, 6), 1);
            u.settings().save(s);
            u.anken().save(new Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"));
            int rid = u.syncRuns().start(true, AT);
            u.syncRuns().finish(rid, AT, "ok", 12, null);
            u.commit();
        }
        try (UnitOfWork u = db.begin()) {
            TrackerSettings s = u.settings().load();
            assertThat(s.jira.epics).containsExactly("PAY-1100");
            assertThat(s.nameRules.aliases.get(StepKind.CREATE)).isEqualTo("Create, Tạo");
            assertThat(s.workingHours.days()).containsExactly(1, 2, 3, 4, 5, 6);
            assertThat(u.anken().list()).containsExactly(new Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"));
            SyncRun r = u.syncRuns().latest();
            assertThat(List.of(r.status(), r.issues(), r.full(), r.startedAt())).containsExactly("ok", 12, true, AT);
            assertThat(u.syncRuns().latestOk()).isEqualTo(r);
        }
    }

    @Test
    void settingsAreStoredAsTheOriginalJsonDocuments() throws Exception {
        try (UnitOfWork u = db.begin()) {
            u.settings().save(new TrackerSettings());
            u.commit();
        }
        Map<String, Object> docs = new LinkedHashMap<>();
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("tracker.db"));
             var rs = c.createStatement().executeQuery("select key, value from settings")) {
            while (rs.next()) {
                docs.put(rs.getString(1), new ObjectMapper().readValue(rs.getString(2), Object.class));
            }
        }
        assertThat(docs.keySet()).containsExactlyInAnyOrder("jira", "field_mapping", "name_rules", "status_mapping", "thresholds",
                "working_hours", "default_country");
        assertThat(docs.get("field_mapping")).isEqualTo(Map.of("planned_start", "customfield_10020", "planned_end", "customfield_10016",
                "effort", "timeoriginalestimate"));
        assertThat(docs.get("default_country")).isEqualTo("VN");
    }

    @Test
    void secretBoxRoundTripAndKeyFile() {
        FernetSecretBox box = FernetSecretBox.fromKeyFile(tmp.resolve("secret.key"));
        String token = box.encrypt("ATATT3xFfGF0-real-token");
        assertThat(token).isNotEqualTo("ATATT3xFfGF0-real-token").startsWith("gAAAAA");
        assertThat(FernetSecretBox.fromKeyFile(tmp.resolve("secret.key")).decrypt(token)).isEqualTo("ATATT3xFfGF0-real-token");
        assertThat(box.encrypt("")).isEmpty();
        assertThat(box.decrypt("")).isEmpty();
    }

    @Test
    void secretBoxReadsTokensOfTheOriginalBackend() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> fx = new ObjectMapper().readValue(Files.readString(Path.of(
                PersistenceTest.class.getResource("/fernet_python.json").toURI())), Map.class);
        assertThat(new FernetSecretBox(fx.get("key")).decrypt(fx.get("token"))).isEqualTo(fx.get("plain"));
    }
}
