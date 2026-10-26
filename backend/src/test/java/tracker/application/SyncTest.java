package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import tracker.TrackerTest;
import tracker.application.SyncService.SyncResult;
import tracker.application.dto.ChangeSnapshot;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.UserSnapshot;
import tracker.application.dto.WorklogSnapshot;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraReader;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SettingsRepository;
import tracker.application.ports.SyncRunRepository;
import tracker.application.settings.TrackerSettings;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.Member;
import tracker.domain.MemberIds;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.TaskTemplate;
import tracker.domain.WorkStructure;

/** Syncs from a stub Jira into an empty database. */
@TrackerTest
class SyncTest {
    static final UserSnapshot AN = new UserSnapshot("acc-an", "Nguyễn Thị An", "an@acme.vn");
    static final UserSnapshot MINH = new UserSnapshot("acc-minh", "Hoàng Minh");
    static final UserSnapshot NEW = new UserSnapshot("acc-new", "Lê Văn Mới");
    static final EpicSnapshot EPIC = new EpicSnapshot("PAY-1100", "PAY", "Payment flow revision", "2026-11-20");
    static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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
        public List<IssueSnapshot> search(List<String> epicKeys, Instant updatedSince) {
            since.add(updatedSince);
            if (fail != null) {
                throw fail;
            }
            return new ArrayList<>(issues);
        }

        @Override
        public Optional<EpicSnapshot> findEpic(String key) {
            return Optional.ofNullable(epics.get(key));
        }

        @Override
        public Optional<IssueSnapshot> findIssue(String key) {
            return issues.stream().filter(i -> i.key().equals(key)).findFirst();
        }

        @Override
        public List<JiraField> listFields() {
            return List.of();
        }

        @Override
        public boolean testConnection() {
            return fail == null;
        }

        @Override
        public List<tracker.application.dto.JiraTransition> transitions(String key) {
            return List.of();
        }

        @Override
        public List<tracker.application.dto.JiraIssueType> issueTypes(String project) {
            return List.of();
        }

    }

    @Autowired
    SettingsRepository settings;
    @Autowired
    MemberRepository members;
    @Autowired
    IssueRepository issueRepository;
    @Autowired
    AnkenRepository anken;
    @Autowired
    SyncRunRepository runs;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        members.save(new Member("an", "An", "Nguyễn Thị An", Role.BA, "VN", "#111", "", tracker.domain.Permission.USER, false, "acc-an"));
        TrackerSettings s = settings.load();
        s.jira.epics = new ArrayList<>(List.of("PAY-1100"));
        s.statusMapping.byName = new LinkedHashMap<>(Map.of("Resolved", "R"));
        settings.save(s);
    }

    SyncService sync(JiraReader reader) {
        return new SyncService(reader, settings, members, issueRepository, anken, runs, tx, CLOCK);
    }

    SyncResult run(JiraReader reader, boolean full) {
        return sync(reader).run(full);
    }

    Map<String, Issue> issues() {
        Map<String, Issue> out = new HashMap<>();
        issueRepository.findAll().forEach(i -> out.put(i.key(), i));
        return out;
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
    void jiraUserIsLinkedToTheMemberWithTheSameEmail() {
        members.save(new Member("lan", "Lan", "Vo Lan", Role.BA, "VN", "#222", "Lan.Vo@acme.vn", tracker.domain.Permission.USER, false, null));
        UserSnapshot lan = new UserSnapshot("acc-lan", "Lan Vo", "lan.vo@acme.vn");
        run(new StubReader(List.of(snap("PAY-1102", "[Implement_Coding] Payment flow revision", "PAY-1100"),
                snap("PAY-1301", "Something", "PAY-1102", "To Do", "new", lan, Map.of(), List.of(), List.of()))), true);
        assertThat(members.findAll().stream().filter(m -> m.name().startsWith("Lan"))).hasSize(1);
        assertThat(members.findByEmail("lan.vo@acme.vn").orElseThrow().jiraAccountId()).isEqualTo("acc-lan");
        assertThat(issues().get("PAY-1301").assigneeId()).isEqualTo("lan");
    }

    @Test
    void memberWithAnotherJiraAccountIsNotRelinked() {
        members.save(new Member("lan", "Lan", "Vo Lan", Role.BA, "VN", "#222", "lan.vo@acme.vn", tracker.domain.Permission.USER, false, "acc-other"));
        UserSnapshot lan = new UserSnapshot("acc-lan", "Lan Vo", "lan.vo@acme.vn");
        run(new StubReader(List.of(snap("PAY-1102", "[Implement_Coding] Payment flow revision", "PAY-1100"),
                snap("PAY-1301", "Something", "PAY-1102", "To Do", "new", lan, Map.of(), List.of(), List.of()))), true);
        assertThat(members.findByEmail("lan.vo@acme.vn").orElseThrow().jiraAccountId()).isEqualTo("acc-other");
        assertThat(issues().get("PAY-1301").assigneeId()).isEqualTo("lan-vo");
    }

    static final IssueSnapshot UC_TASK = snap("PAY-1110", "Unit Code implement", "PAY-1100");
    static final IssueSnapshot FUT_TASK = snap("PAY-1111", "FUT create test case", "PAY-1100");
    static final IssueSnapshot QNA_TASK = snap("PAY-1112", "QnA management for FUT", "PAY-1100");
    static final IssueSnapshot WR_TASK = snap("PAY-1113", "Work Request management", "PAY-1100");

    /** The company's structure: Tasks recognised by name, sub-tasks named [prefix][function]_Step. */
    void configure(String rule) {
        TrackerSettings s = settings.load();
        s.nameRules.rule = rule;
        s.nameRules.aliases.put(StepKind.BAL_REVIEW, "BALReview, BAL Review");
        s.structure = new WorkStructure(List.of("UC", "FUT"), List.of(new TaskTemplate("Unit Code implement", "UC", "UCI"),
                new TaskTemplate("FUT create test case", "FUT", "FUTTC"), new TaskTemplate("QnA management for *", "FUT", "", false)));
        settings.save(s);
    }

    @Test
    void taskPhaseAndFunctionStepsFollowTheConfiguredStructure() {
        configure("[{prefix}][{function_name}]_{step}");
        run(new StubReader(List.of(UC_TASK, FUT_TASK, QNA_TASK, snap("PAY-1201", "[UCI][Login]_Create", "PAY-1110"),
                snap("PAY-1202", "[FUTTC][Login]_Create", "PAY-1111"), snap("PAY-1203", "[FUTTC][Login]_BALReview", "PAY-1111"),
                snap("PAY-1204", "[FUTTC][Login]_Create", "PAY-1112"))), true);
        Map<String, Issue> got = issues();
        assertThat(List.of(got.get("PAY-1110").phase(), got.get("PAY-1111").phase(), got.get("PAY-1112").phase()))
                .containsExactly("UC", "FUT", "FUT");
        Issue c = got.get("PAY-1202");
        assertThat(List.of(c.functionId(), c.functionName(), c.step(), c.phase()))
                .containsExactly("Login", "Login", StepKind.CREATE, "FUT");
        assertThat(got.get("PAY-1203").step()).isEqualTo(StepKind.BAL_REVIEW);
        assertThat(List.of(got.get("PAY-1201").functionId(), got.get("PAY-1201").phase())).containsExactly("Login", "UC");
        Issue q = got.get("PAY-1204"); // matches the rule, but Q&A tasks have the workflow turned off
        assertThat(q.functionId()).isNull();
        assertThat(q.step()).isNull();
    }

    static final IssueSnapshot E2_EPIC_TASK = snap("PAY-2102", "[Implement_Coding] Other flow", "PAY-2000");

    private Map<String, EpicSnapshot> twoEpics() {
        return Map.of("PAY-1100", EPIC, "PAY-2000", new EpicSnapshot("PAY-2000", "PAY", "Other flow", "2026-12-01"));
    }

    private void twoEpicSettings(TrackerSettings.AnkenSettings e1) {
        TrackerSettings s = settings.load();
        s.jira.epics = new ArrayList<>(List.of("PAY-1100", "PAY-2000"));
        s.anken.put("PAY-1100", e1);
        s.anken.put("PAY-2000", TrackerSettings.AnkenSettings.copyOf(s.structure, s.nameRules));
        settings.save(s);
    }

    @Test
    void eachEpicIsParsedWithItsOwnRule() {
        TrackerSettings.AnkenSettings e1 = TrackerSettings.AnkenSettings.copyOf(settings.load().structure, settings.load().nameRules);
        e1.nameRules.rule = "[{prefix}][{function_name}]_{step}";
        twoEpicSettings(e1);
        run(new StubReader(List.of(snap("PAY-1102", "[Implement_Coding] Payment flow revision", "PAY-1100"), E2_EPIC_TASK,
                snap("PAY-1201", "[Implement_Coding][Login]_Create", "PAY-1102"),
                snap("PAY-1202", "[Implement_Coding][Login]_BAL Review", "PAY-1102"),
                snap("PAY-2201", "[Implement_Coding][F07] Search – Create", "PAY-2102"),
                snap("PAY-2202", "[Implement_Coding][F07] Search – BAL Review", "PAY-2102")), twoEpics(), null), true);
        Map<String, Issue> got = issues();
        assertThat(List.of(got.get("PAY-1201").functionId(), got.get("PAY-1201").step())).containsExactly("Login", StepKind.CREATE);
        assertThat(got.get("PAY-1202").step()).isEqualTo(StepKind.BAL_REVIEW);
        assertThat(List.of(got.get("PAY-2201").functionId(), got.get("PAY-2201").step())).containsExactly("F07", StepKind.CREATE);
        assertThat(got.get("PAY-2202").step()).isEqualTo(StepKind.BAL_REVIEW);
    }

    @Test
    void phaseOfTaskUsesTheEpicsStructure() {
        TrackerSettings.AnkenSettings e1 = TrackerSettings.AnkenSettings.copyOf(settings.load().structure, settings.load().nameRules);
        e1.structure = new WorkStructure(List.of("ITA"), List.of(new TaskTemplate("ITA *", "ITA", "ITA")));
        twoEpicSettings(e1);
        run(new StubReader(List.of(snap("PAY-1110", "ITA Prepare", "PAY-1100"), snap("PAY-2110", "ITA Prepare", "PAY-2000")),
                twoEpics(), null), true);
        Map<String, Issue> got = issues();
        assertThat(got.get("PAY-1110").phase()).isEqualTo("ITA");
        assertThat(got.get("PAY-2110").phase()).isNotEqualTo("ITA");
    }

    @Test
    void workflowSwitchIsPerEpic() {
        TrackerSettings.AnkenSettings e1 = TrackerSettings.AnkenSettings.copyOf(settings.load().structure, settings.load().nameRules);
        e1.nameRules.rule = "[{prefix}][{function_name}]_{step}";
        e1.structure = new WorkStructure(List.of("ITA"), List.of(new TaskTemplate("ITA *", "ITA", "ITA", false)));
        TrackerSettings s = settings.load();
        s.jira.epics = new ArrayList<>(List.of("PAY-1100", "PAY-2000"));
        s.anken.put("PAY-1100", e1);
        TrackerSettings.AnkenSettings e2 = TrackerSettings.AnkenSettings.copyOf(s.structure, s.nameRules);
        e2.nameRules.rule = "[{prefix}][{function_name}]_{step}";
        e2.structure = new WorkStructure(List.of("ITA"), List.of(new TaskTemplate("ITA *", "ITA", "ITA", true)));
        s.anken.put("PAY-2000", e2);
        settings.save(s);
        run(new StubReader(List.of(snap("PAY-1110", "ITA Prepare", "PAY-1100"), snap("PAY-2110", "ITA Prepare", "PAY-2000"),
                snap("PAY-1201", "[ITA][Login]_Create", "PAY-1110"), snap("PAY-2201", "[ITA][Login]_Create", "PAY-2110")),
                twoEpics(), null), true);
        Map<String, Issue> got = issues();
        assertThat(got.get("PAY-1201").functionId()).isNull();
        assertThat(got.get("PAY-2201").functionId()).isEqualTo("Login");
    }

    @Test
    void taskWithoutATemplateKeepsTheBracketPhase() {
        configure("[{prefix}][{function_name}]_{step}");
        run(new StubReader(List.of(PARENT, WR_TASK)), true);
        Map<String, Issue> got = issues();
        assertThat(got.get("PAY-1102").phase()).isEqualTo("Implement_Coding");
        assertThat(got.get("PAY-1113").phase()).isEmpty();
    }

    @Test
    void remainingEstimateIsReadInHours() {
        run(new StubReader(List.of(PARENT, snap("PAY-1201", "x", "PAY-1102", "In Progress", "indeterminate", AN,
                Map.of("timeoriginalestimate", 36000, "timeestimate", 7200), List.of(), List.of()))), true);
        assertThat(issues().get("PAY-1201").remainingH()).isEqualTo(2.0);
    }

    @Test
    void missingRemainingFallsBackToOriginalEstimate() {
        run(new StubReader(List.of(PARENT, snap("PAY-1201", "x", "PAY-1102", "In Progress", "indeterminate", AN,
                Map.of("timeoriginalestimate", 36000), List.of(), List.of()))), true);
        assertThat(issues().get("PAY-1201").remainingH()).isEqualTo(10.0);
    }

    @Test
    void reporterIsTheFirstOneNotTheCurrentOne() {
        IssueSnapshot changed = new IssueSnapshot("PAY-1201", "PAY", "x", "In Progress", "indeterminate", AN, "PAY-1102", "2026-09-01",
                "2026-10-06T10:00:00+00:00", Map.of(), List.of(), List.of(new ChangeSnapshot("2026-09-20", "reporter", "acc-minh", "acc-an", MINH, AN),
                        new ChangeSnapshot("2026-09-25", "reporter", "acc-an", "acc-new", AN, NEW)), NEW);
        IssueSnapshot kept = new IssueSnapshot("PAY-1202", "PAY", "y", "In Progress", "indeterminate", AN, "PAY-1102", "2026-09-01",
                "2026-10-06T10:00:00+00:00", Map.of(), List.of(), List.of(), MINH);
        run(new StubReader(List.of(PARENT, changed, kept)), true);
        String minh = members.findByJiraAccountId("acc-minh").orElseThrow().id();
        assertThat(issues().get("PAY-1201").reporterId()).isEqualTo(minh);
        assertThat(issues().get("PAY-1202").reporterId()).isEqualTo(minh);
        assertThat(issues().get("PAY-1102").reporterId()).isNull();
    }

    @Test
    void ruleWithoutFunctionIdentitySyncsWithoutFunctions() {
        configure("[{prefix}] {step}");
        SyncResult r = run(new StubReader(List.of(FUT_TASK, snap("PAY-1202", "[FUTTC] Create", "PAY-1111"))), true);
        assertThat(r.status()).isEqualTo("ok");
        assertThat(issues().get("PAY-1202").functionId()).isNull();
    }

    @Test
    void unknownAccountBecomesMemberWithoutRole() {
        run(new StubReader(List.of(PARENT, OTHER)), true);
        Member m = members.findByJiraAccountId("acc-new").orElseThrow();
        assertThat(List.of(m.id(), m.name(), m.country())).containsExactly("le-van-moi", "Lê Văn Mới", "VN");
        assertThat(m.role()).isNull();
        assertThat(members.findByJiraAccountId("acc-minh")).isEmpty(); // only accounts that appear on synced issues are created
    }

    @Test
    void epicBecomesAnkenAndStatusesAreRecorded() {
        run(new StubReader(List.of(PARENT, CREATE, REVIEW)), true);
        assertThat(anken.findAll()).containsExactly(new tracker.domain.Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"));
        assertThat(settings.load().statusMapping.seen).containsExactly(Map.entry("In Progress", "indeterminate"),
                Map.entry("Resolved", "done"));
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
        settings.save(new TrackerSettings());
        assertThat(run(new StubReader(List.of()), true).status()).isEqualTo("ok");
    }

    @Test
    void refreshResyncsOnlyTheGivenKeys() {
        StubReader reader = new StubReader(List.of(PARENT, CREATE, REVIEW));
        run(reader, true);
        reader.issues = List.of(PARENT, snap("PAY-1201", CREATE.summary(), "PAY-1102", "Closed", "done", AN, Map.of(), List.of(), List.of()),
                REVIEW);
        sync(reader).refresh(List.of("PAY-1201", "PAY-9999"));
        assertThat(issues()).doesNotContainKey("PAY-9999");
        Issue got = issues().get("PAY-1201");
        assertThat(got.status()).isEqualTo(Status.CLOSED);
        assertThat(got.phase()).isEqualTo("Implement_Coding");
    }

    @Test
    void concurrentRunsDoNotOverlap() throws InterruptedException {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        StubReader slow = new StubReader(List.of(PARENT)) {
            @Override
            public List<IssueSnapshot> search(List<String> epicKeys, Instant updatedSince) {
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
        SyncService sync = sync(slow);
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
        assertThat(MemberIds.slugify("Lê Văn Mới")).isEqualTo("le-van-moi");
        assertThat(MemberIds.slugify("Đặng Đức")).isEqualTo("dang-duc");
        assertThat(MemberIds.slugify("!!!")).isEqualTo("member");
        // colours come from a CRC32 of the member id, so a member keeps its colour across syncs
        assertThat(MemberIds.color("le-van-moi")).isEqualTo("oklch(0.56 0.14 35)");
        assertThat(MemberIds.color("an")).isEqualTo("oklch(0.55 0.15 0)");
        assertThat(MemberIds.color("tanaka")).isEqualTo("oklch(0.5 0.1 210)");
        assertThat(MemberIds.color("x-2")).isEqualTo("oklch(0.48 0.04 260)");
    }
}
