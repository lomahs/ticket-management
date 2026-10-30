package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Conflict;
import tracker.application.AppError.Forbidden;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraRejected;
import tracker.application.ports.JiraWriteLog;
import tracker.application.service.AnkenService.FunctionDraft;
import tracker.application.service.AnkenService;
import tracker.application.service.CalendarService;
import tracker.application.service.EffortService;
import tracker.application.service.JiraSettingsService;
import tracker.application.service.PlanningService.PlanChange;
import tracker.application.service.PlanningService;
import tracker.application.service.SystemService;
import tracker.application.service.TeamSettingsService;
import tracker.application.service.TicketService;
import tracker.application.service.TicketService.TicketChangePreview;
import tracker.application.service.TicketService.ExpectedChange;
import tracker.application.service.TicketService.ExpectedUpdate;
import tracker.application.service.TicketService.TicketChanges;
import tracker.application.service.WorkService;
import tracker.application.settings.SettingsStore;
import tracker.application.settings.TrackerSettings;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScope;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.LeaveType;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.TaskTemplate;
import tracker.domain.Thresholds;
import tracker.domain.Viewer;
import tracker.domain.Worklog;
import tracker.domain.WorkingHours;
import tracker.domain.WorkStructure;

/** Services that write: each test starts from fresh demo data. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ServiceWritesTest {
    /** Every tracked epic of the demo: the scope of a viewer who sees all anken. */
    static final AnkenScope ALL = new AnkenScope(Set.copyOf(Dataset.TRACKED_EPICS));

    @Autowired
    AnkenService anken;
    @Autowired
    PlanningService planning;
    @Autowired
    EffortService effort;
    @Autowired
    CalendarService calendar;
    @Autowired
    TicketService tickets;
    @Autowired
    SystemService system;
    @Autowired
    WorkService work;
    @Autowired
    JiraSettingsService jiraSettings;
    @Autowired
    TeamSettingsService teamSettings;

    static FunctionDraft draft(String parent, String name, double hours) {
        return new FunctionDraft(parent, name, "an", "minh", "tanaka", hours, new ArrayList<>(Collections.nCopies(5, null)));
    }

    @Test
    void createFunctionWritesToJiraAndSyncsBack() {
        FunctionDraft d = draft("PAY-1102", "Installment payment", 8);
        List<String> keys = anken.createFunction(ADMIN, ALL, "PAY-1100", d, null);
        assertThat(keys).containsExactly("PAY-1501", "PAY-1502", "PAY-1503", "PAY-1504", "PAY-1505");
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", null, null, null);
        AnkenService.FunctionRow last = m.functions().get(m.functions().size() - 1);
        assertThat(last.id()).isEqualTo("F13");
        assertThat(last.cells()).extracting(TaskCard::key).containsExactlyElementsOf(keys);
        assertThat(m.nextFunctionId()).isEqualTo("F14");
        assertThatThrownBy(() -> anken.createFunction(ADMIN, ALL, "PAY-1100", d, "F13")).isInstanceOf(Conflict.class);
    }

    @Test
    void createsAThreeStepFunction() {
        FunctionDraft d = new FunctionDraft("PAY-1102", "Execute test case", "an", "minh", null, 8,
                new ArrayList<>(Collections.nCopies(3, null)), 3);
        assertThat(anken.previewFunction(ALL, "PAY-1100", d).steps()).extracting(AnkenService.PreviewStep::step)
                .containsExactly("create", "balr", "fixb");
        List<String> keys = anken.createFunction(ADMIN, ALL, "PAY-1100", d, null);
        assertThat(keys).hasSize(3);
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", null, null, null);
        AnkenService.FunctionRow last = m.functions().get(m.functions().size() - 1);
        assertThat(last.cells()).extracting(TaskCard::key).containsExactlyElementsOf(keys);
        FunctionDraft four = new FunctionDraft("PAY-1102", "Bad", "an", "minh", "tanaka", 8, List.of(), 4);
        assertThatThrownBy(() -> anken.previewFunction(ALL, "PAY-1100", four)).isInstanceOf(Invalid.class)
                .extracting("field").isEqualTo("steps");
        FunctionDraft noJm = new FunctionDraft("PAY-1102", "Bad", "an", "minh", null, 8, List.of(), 5);
        assertThatThrownBy(() -> anken.previewFunction(ALL, "PAY-1100", noJm)).isInstanceOf(Invalid.class)
                .extracting("field").isEqualTo("jm");
    }

    /** The company's naming: Tasks recognised by name, sub-tasks named [prefix][function name]_Step without a function ID. */
    @Test
    void createFunctionWithCompanyNaming(@Autowired SettingsStore settings, @Autowired SyncService sync) {
        settings.update(s -> {
            TrackerSettings.AnkenSettings a = s.anken.computeIfAbsent("PAY-1100", e -> TrackerSettings.AnkenSettings.copyOf(s.structure, s.nameRules));
            a.nameRules.rule = "[{prefix}][{function_name}]_{step}";
            a.nameRules.aliases = new java.util.EnumMap<>(Map.of(StepKind.CREATE, "Create", StepKind.BAL_REVIEW, "BALReview",
                    StepKind.FIX_BAL, "FixCommentBAL", StepKind.JM_REVIEW, "JMReview", StepKind.FIX_JM, "FixCommentJM"));
            a.structure = new WorkStructure(List.of("Prepare", "Implement_Coding", "Function Unit Test"),
                    List.of(new TaskTemplate("[Implement_Coding] *", "Implement_Coding", "UCI"),
                            new TaskTemplate("[Prepare] *", "Prepare", "PRE", false)));
        });
        sync.run(true);
        FunctionDraft d = draft("PAY-1102", "Login", 8);
        AnkenService.FunctionPreview p = anken.previewFunction(ALL, "PAY-1100", d);
        assertThat(List.of(p.phase(), p.prefix(), p.functionId())).containsExactly("Implement_Coding", "UCI", "");
        assertThat(p.steps()).extracting(AnkenService.PreviewStep::summary).containsExactly("[UCI][Login]_Create",
                "[UCI][Login]_BALReview", "[UCI][Login]_FixCommentBAL", "[UCI][Login]_JMReview", "[UCI][Login]_FixCommentJM");
        List<String> keys = anken.createFunction(ADMIN, ALL, "PAY-1100", d, null);
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", List.of("PAY-1102"), null, null); // the demo's own functions no longer match the rule
        assertThat(m.functions()).extracting(f -> f.id() + "/" + f.name()).containsExactly("Login/Login");
        assertThat(m.functions().get(0).cells()).extracting(TaskCard::key).containsExactlyElementsOf(keys);
        assertThatThrownBy(() -> anken.createFunction(ADMIN, ALL, "PAY-1100", d, null)).isInstanceOf(Conflict.class); // same name, same Task
        assertThatThrownBy(() -> anken.previewFunction(ALL, "PAY-1100", draft("PAY-1101", "X", 8))).isInstanceOf(Invalid.class);
    }

    @Test
    void saveStructureResyncsPhases() {
        jiraSettings.saveStructure("PAY-1100", new WorkStructure(List.of("Coding", "Prepare"),
                List.of(new TaskTemplate("[Implement_Coding] *", "Coding", "UCI"))));
        AnkenService.AnkenCard card = anken.overview(ALL).stream().filter(c -> c.key().equals("PAY-1100")).findFirst().orElseThrow();
        assertThat(card.phases()).extracting(x -> x.name()).containsExactly("Coding", "Prepare", "Other"); // an unconfigured "[Phase]" is not a phase
    }

    static Issue task(String key, String summary) {
        return new Issue(key, "CRM", "CRM-400", "CRM-400", IssueKind.PARENT, summary, "", Status.WIP, "an", null, null, 0, 0,
                "2026-09-01", "In Progress", null, null, null, List.of(), List.of());
    }

    static Issue sub(String key, String parent, Status status, double logged, double remaining) {
        return new Issue(key, "CRM", "CRM-400", parent, IssueKind.SUBTASK, key, "", status, "an", "2026-10-01", "2026-10-09", 8,
                remaining, "2026-09-01", "", null, null, null, List.of(new Worklog("2026-10-01", "an", logged)), List.of());
    }

    @Test
    void overviewPhasesFollowTaskTitleGroups(@Autowired IssueRepository issues) {
        List.of(task("CRM-901", "[Hub][LocalTask] ITA Prepare"), task("CRM-902", "[Hub[LocalTask] ITA Execute"),
                task("CRM-903", "[Hub][LocalTask] WorkRequest"), sub("CRM-911", "CRM-901", Status.CLOSED, 3, 0),
                sub("CRM-912", "CRM-902", Status.WIP, 1, 4)).forEach(issues::save);
        List<AnkenService.AnkenCard> cards = anken.overview(ALL).stream().filter(c -> c.key().equals("CRM-400")).toList();
        var phases = cards.get(0).phases();
        assertThat(phases).extracting(p -> p.name()).containsExactly("Implement_Coding", "ITA", "Other");
        assertThat(List.of(phases.get(1).pct(), phases.get(1).closed(), phases.get(1).total())).containsExactly(50, 1, 2);
        assertThat(List.of(phases.get(1).start(), phases.get(1).end())).containsExactly("2026-10-01", "2026-10-09");
        assertThat(List.of(phases.get(2).pct(), phases.get(2).total())).containsExactly(0, 0);
    }

    /** Without a JiraWriter nothing is written to Jira; Planning pushes to the demo site only. */
    @Test
    void writesNeedJiraWrite(@Autowired TrackerContextLoader context, @Autowired SyncService sync, @Autowired JiraReader reader,
                             @Autowired JiraWriteLog log, @Autowired java.time.Clock clock,
                             @Autowired tracker.application.service.AllocationService allocations) {
        JiraCalls none = new JiraCalls(Optional.empty(), log, clock);
        assertThatThrownBy(() -> new AnkenService(context, none, reader, sync).createFunction(ADMIN, ALL, "PAY-1100", draft("PAY-1102", "X", 8), null))
                .isInstanceOf(CapabilityDisabled.class);
        assertThatThrownBy(() -> new PlanningService(context, none, new SystemService.Mode("demo"), sync, allocations)
                .push(ADMIN, ALL, List.of(new PlanChange("PAY-1262", "2026-10-12", "binh")))).isInstanceOf(CapabilityDisabled.class);
        assertThatThrownBy(() -> new TicketService(context, none, reader, sync)
                .preview(ALL, "PAY-1213", new TicketChanges(Status.RESOLVED, null, null, null, null, null))).isInstanceOf(CapabilityDisabled.class);
        JiraCalls site = new JiraCalls(Optional.of(r -> new tracker.application.dto.JiraResponse(204, "")), log, clock);
        assertThatThrownBy(() -> new PlanningService(context, site, new SystemService.Mode("jira"), sync, allocations)
                .push(ADMIN, ALL, List.of(new PlanChange("PAY-1262", "2026-10-12", "binh")))).isInstanceOf(CapabilityDisabled.class);
    }

    static final Viewer ADMIN = new Viewer("minh.hoang@acme.vn", "Minh", "minh", Role.BAL, true, AccessStatus.ACTIVE,
            Permissions.defaults(Role.BAL));
    static final Viewer BA_AN = new Viewer("an.nguyen@acme.vn", "An", "an", Role.BA, false, AccessStatus.ACTIVE,
            Permissions.defaults(Role.BA));

    @Test
    void leaveSaveEditDelete() {
        int n = calendar.saveLeave(ALL, new CalendarService.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-02", "2026-11-08", "trip"), ADMIN);
        assertThat(n).isEqualTo(5);
        int id = leaveOn("2026-11-03").id();
        calendar.saveLeave(ALL, new CalendarService.LeaveDraft(id, "an", LeaveType.HALF, "2026-11-03", "2026-11-03", "half"), ADMIN);
        tracker.domain.Leave lv = leaveOn("2026-11-03");
        assertThat(lv.type()).isEqualTo(LeaveType.HALF);
        calendar.deleteLeave(ALL, lv.id(), ADMIN);
        assertThatThrownBy(() -> calendar.deleteLeave(ALL, lv.id(), ADMIN)).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> calendar.saveLeave(ALL, 
                new CalendarService.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-08", "2026-11-02", ""), ADMIN)).isInstanceOf(Invalid.class);
    }

    @Test
    void withoutCalendarWriteOnlyOwnLeaveChanges() {
        assertThat(calendar.saveLeave(ALL, new CalendarService.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-02", "2026-11-02", ""), BA_AN))
                .isEqualTo(1);
        assertThatThrownBy(() -> calendar.saveLeave(ALL, new CalendarService.LeaveDraft(null, "binh", LeaveType.FULL, "2026-11-02",
                "2026-11-02", ""), BA_AN)).isInstanceOf(Forbidden.class).hasMessage("You can only change your own leave");
        int binhs = calendar.month(ALL, "2026-10", null).rows().stream().filter(r -> r.memberId().equals("binh"))
                .flatMap(r -> r.cells().stream()).filter(c -> c.date().equals("2026-10-20")).findFirst().orElseThrow().leave().id();
        // moving someone else's leave onto yourself is still a change of their leave
        assertThatThrownBy(() -> calendar.saveLeave(ALL, new CalendarService.LeaveDraft(binhs, "an", LeaveType.FULL, "2026-11-04",
                "2026-11-04", ""), BA_AN)).isInstanceOf(Forbidden.class);
        assertThatThrownBy(() -> calendar.deleteLeave(ALL, binhs, BA_AN)).isInstanceOf(Forbidden.class);
        Viewer outsider = new Viewer("x@acme.vn", "X", null, null, false, AccessStatus.ACTIVE, Permissions.defaults(null));
        assertThatThrownBy(() -> calendar.saveLeave(ALL, new CalendarService.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-02",
                "2026-11-02", ""), outsider)).isInstanceOf(Forbidden.class);
    }

    private tracker.domain.Leave leaveOn(String date) {
        return calendar.month(ALL, "2026-11", null).rows().stream().filter(r -> r.memberId().equals("an"))
                .flatMap(r -> r.cells().stream()).filter(c -> c.date().equals(date)).findFirst().orElseThrow().leave();
    }

    @Test
    void importPreviewAndConfirm() {
        List<List<String>> rows = List.of(List.of("Bình", "2026-11-10", "2026-11-10", "All day", ""),
                List.of("Duc", "2026-11-13", "2026-11-13", "Half day", ""), List.of("Minh", "2026-11-18", "2026-11-20", "All day", ""));
        CalendarService.ImportPreview p = calendar.importPreview(ALL, rows);
        assertThat(p.rows()).extracting(r -> r.error()).containsExactly("", "Unknown member \"Duc\"", "");
        assertThat(p.workingDays()).isEqualTo(4);
        assertThat(calendar.importConfirm(ALL, rows)).isEqualTo(new CalendarService.ImportResult(4, "2026-11"));
    }

    @Test
    void pushWritesDatesAndAssigneeThenSyncs() {
        planning.push(ADMIN, ALL, List.of(new PlanChange("PAY-1262", "2026-10-12", "binh")));
        TaskCard c = planning.view(ALL, "2026-10-12", 1).rows().stream().filter(r -> r.memberId().equals("binh"))
                .flatMap(r -> r.items().stream()).map(PlanningService.PlanBlock::card).filter(x -> x.key().equals("PAY-1262"))
                .findFirst().orElseThrow();
        assertThat(List.of(c.plannedStart(), c.plannedEnd(), c.assigneeId())).containsExactly("2026-10-12", "2026-10-12", "binh");
    }

    static TicketChanges want(Status status, String assignee, String reporter, String start, String end, Double estimate) {
        return new TicketChanges(status, assignee, reporter, start, end, estimate);
    }

    static List<ExpectedChange> expected(TicketChangePreview p) {
        return p.changes().stream().map(c -> new ExpectedChange(c.field(), c.before(), c.after())).toList();
    }

    /** The review drops fields that would not change and shows the exact requests: one PUT of the changed fields, one transition. */
    @Test
    void previewShowsOnlyWhatChanges() {
        TaskCard c = tickets.detail(ALL, "PAY-1213").card();
        TicketChangePreview p = tickets.preview(ALL, "PAY-1213", want(Status.RESOLVED, "minh", null, c.plannedStart(), "2026-10-08", null));
        assertThat(p.changes()).extracting(ch -> ch.field() + ":" + ch.jiraField() + ":" + ch.after())
                .containsExactly("status:status:R", "assignee:assignee:minh", "planned_end:customfield_10016:2026-10-08");
        assertThat(p.changes().get(0).before()).isEqualTo(c.status());
        assertThat(p.requests()).extracting(r -> r.method() + " " + r.path() + " " + r.body()).containsExactly(
                "PUT /rest/api/3/issue/PAY-1213 {\"fields\":{\"assignee\":{\"accountId\":\"acc-minh\"},\"customfield_10016\":\"2026-10-08\"}}",
                "POST /rest/api/3/issue/PAY-1213/transitions {\"transition\":{\"id\":\"31\"}}");
        assertThat(List.of(p.transition(), p.jiraStatus())).containsExactly("Resolve", "Resolved");
        assertThatThrownBy(() -> tickets.preview(ALL, "PAY-1213", want(null, null, null, "2026-10-20", null, null))).isInstanceOf(Invalid.class);
    }

    @Test
    void reporterAndEstimateGoThroughTheirOwnFields() {
        TicketChangePreview p = tickets.preview(ALL, "PAY-1213", want(null, null, "chi", null, null, 6.5));
        assertThat(p.changes()).extracting(ch -> ch.field() + ":" + ch.after()).containsExactly("reporter:chi", "estimate:6.5");
        assertThat(p.requests()).extracting(r -> r.body()).containsExactly(
                "{\"fields\":{\"reporter\":{\"accountId\":\"acc-chi\"},\"timetracking\":{\"originalEstimate\":\"390m\"}}}");
        assertThat(p.estimateTracking()).isTrue();
        tickets.apply(ADMIN, ALL, "PAY-1213", expected(p));
        assertThat(tickets.detail(ALL, "PAY-1213").card().estimateH()).isEqualTo(6.5);
    }

    @Test
    void applyWritesLogsAndSyncs(@Autowired JiraWriteLog log) {
        TicketChangePreview p = tickets.preview(ALL, "PAY-1213", want(Status.RESOLVED, "minh", null, null, "2026-10-08", null));
        TaskCard c = tickets.apply(ADMIN, ALL, "PAY-1213", expected(p)).card();
        assertThat(List.of(c.status(), c.assigneeId(), c.plannedEnd(), c.late())).containsExactly("R", "minh", "2026-10-08", false);
        assertThat(log.recent(10, false)).extracting(e -> e.source() + " " + e.actorEmail() + " " + e.method() + " " + e.status())
                .containsExactly("ticket_panel minh.hoang@acme.vn POST 204", "ticket_panel minh.hoang@acme.vn PUT 204");
    }

    /** Jira changed after the review: nothing is written, the requests are logged as not sent. */
    @Test
    void applyRefusesWhenJiraChangedSinceTheReview(@Autowired JiraWriteLog log, @Autowired tracker.adapters.fake.FakeJira jira) {
        TicketChangePreview p = tickets.preview(ALL, "PAY-1213", want(null, "minh", null, null, "2026-10-08", null));
        jira.send(new tracker.application.dto.JiraRequest("PUT", "/rest/api/3/issue/PAY-1213", "{\"fields\":{\"customfield_10016\":\"2026-10-15\"}}"));
        assertThatThrownBy(() -> tickets.apply(ADMIN, ALL, "PAY-1213", expected(p))).isInstanceOf(Conflict.class)
                .hasMessageContaining("planned_end changed in Jira after the review");
        assertThat(jira.findIssue("PAY-1213").orElseThrow().assignee().accountId()).isNotEqualTo("acc-minh");
        assertThat(log.recent(10, true)).extracting(e -> e.status() + " " + e.error()).containsExactly(
                "0 Not sent: planned_end changed in Jira after the review");
    }

    @Test
    void boardListsTheTasksOfTheAnkenWithTheirSubtasks() {
        TicketService.TicketBoard b = tickets.board(ALL, "PAY-1100");
        assertThat(b.tasks()).extracting(t -> t.task().key()).contains("PAY-1101", "PAY-1102", "PAY-1103");
        TicketService.BoardTask coding = b.tasks().stream().filter(t -> t.task().key().equals("PAY-1102")).findFirst().orElseThrow();
        assertThat(coding.workflow()).isTrue();
        assertThat(coding.subtasks()).extracting(TicketService.BoardRow::key).contains("PAY-1213");
        assertThat(b.templates()).hasSize(2);
        assertThat(b.site()).isEqualTo("https://acme-pay.atlassian.net");
    }

    static TicketService.NewTicket newTask(String ref, String summary) {
        return new TicketService.NewTicket(ref, false, null, null, summary, "an", "minh", "2026-10-12", "2026-10-23", null);
    }

    static TicketService.NewTicket newSub(String ref, String parentKey, String parentRef, String summary) {
        return new TicketService.NewTicket(ref, true, parentKey, parentRef, summary, "an", null, "2026-10-12", "2026-10-13", 4.0);
    }

    /** One review for updates and new tickets; a sub-task under a new Task gets the key its Task was given. */
    @Test
    void batchUpdatesAndCreatesTasksWithSubtasks(@Autowired JiraWriteLog log) {
        List<TicketService.TicketUpdate> updates = List.of(new TicketService.TicketUpdate("PAY-1213", want(null, null, null, null, "2026-10-08", null)));
        List<TicketService.NewTicket> creates = List.of(newTask("t1", "[Prepare] Work requests"), newSub("s1", null, "t1", "Ask JM about refunds"),
                newSub("s2", "PAY-1101", null, "Collect sample data"));
        TicketService.BatchPreview p = tickets.previewBatch(ALL, "PAY-1100", updates, creates);
        assertThat(p.updates()).extracting(TicketChangePreview::key).containsExactly("PAY-1213");
        assertThat(p.creates()).extracting(c -> c.parent()).containsExactly("PAY-1100", "<new:t1>", "PAY-1101");
        assertThat(p.creates().get(0).request().body()).contains("\"issuetype\":{\"id\":\"10001\"}", "\"reporter\":{\"accountId\":\"acc-minh\"}");
        assertThat(p.creates().get(1).request().body()).contains("\"parent\":{\"key\":\"<new:t1>\"}", "\"originalEstimate\":\"240m\"");

        TicketService.BatchResult r = tickets.applyBatch(ADMIN, ALL, "PAY-1100",
                List.of(new ExpectedUpdate("PAY-1213", expected(p.updates().get(0)))), creates);
        assertThat(r.updated()).containsExactly("PAY-1213");
        assertThat(r.created()).containsExactly(Map.entry("t1", "PAY-1501"), Map.entry("s1", "PAY-1502"), Map.entry("s2", "PAY-1503"));
        TicketService.BoardTask added = tickets.board(ALL, "PAY-1100").tasks().stream().filter(t -> t.task().key().equals("PAY-1501")).findFirst().orElseThrow();
        assertThat(added.subtasks()).extracting(TicketService.BoardRow::summary).containsExactly("Ask JM about refunds");
        assertThat(log.recent(10, false)).extracting(e -> e.source() + " " + e.method() + " " + e.status()).containsExactly(
                "tickets POST 201", "tickets POST 201", "tickets POST 201", "tickets PUT 204");
        assertThat(log.recent(10, false).get(1).body()).contains("\"parent\":{\"key\":\"PAY-1501\"}");
    }

    @Test
    void batchIsRefusedWholeWhenOneTicketChanged(@Autowired tracker.adapters.fake.FakeJira jira) {
        List<TicketService.TicketUpdate> updates = List.of(new TicketService.TicketUpdate("PAY-1213", want(null, null, null, null, "2026-10-08", null)));
        TicketService.BatchPreview p = tickets.previewBatch(ALL, "PAY-1100", updates, List.of());
        jira.send(new tracker.application.dto.JiraRequest("PUT", "/rest/api/3/issue/PAY-1213", "{\"fields\":{\"customfield_10016\":\"2026-10-15\"}}"));
        assertThatThrownBy(() -> tickets.applyBatch(ADMIN, ALL, "PAY-1100", List.of(new ExpectedUpdate("PAY-1213", expected(p.updates().get(0)))),
                List.of(newTask("t1", "[Prepare] Work requests")))).isInstanceOf(Conflict.class).hasMessageContaining("PAY-1213 (planned_end)");
        assertThat(jira.findIssue("PAY-1501")).isEmpty();
        assertThatThrownBy(() -> tickets.previewBatch(ALL, "PAY-1100", List.of(), List.of(newSub("s1", null, "t9", "X"))))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void jiraRefusalIsLoggedAndReported(@Autowired JiraWriteLog log, @Autowired SettingsStore settings) {
        settings.update(s -> s.fieldMapping.plannedEnd = "customfield_99999");
        TicketChangePreview p = tickets.preview(ALL, "PAY-1213", want(null, null, null, null, "2026-10-08", null));
        assertThatThrownBy(() -> tickets.apply(ADMIN, ALL, "PAY-1213", expected(p))).isInstanceOf(JiraRejected.class);
        assertThat(log.recent(10, true)).extracting(JiraWriteLog.JiraLogEntry::status).containsExactly(400);
    }

    @Test
    void saveJiraKeepsTokenWhenBlankAndValidatesInterval() {
        JiraSettingsService.JiraSettingsView s = jiraSettings.saveJira("https://acme-pay.atlassian.net", "new@acme.vn", null, "60");
        assertThat(List.of(s.email(), s.interval(), s.status())).containsExactly("new@acme.vn", "60", "ok");
        assertThatThrownBy(() -> jiraSettings.saveJira("https://acme-pay.atlassian.net", "new@acme.vn", null, "7"))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void addAndRemoveEpic() {
        assertThatThrownBy(() -> jiraSettings.addEpic("pay-1100")).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> jiraSettings.addEpic("PAY-1201")).isInstanceOf(NotFound.class)
                .hasMessageContaining("was not found or is not an Epic");
        assertThatThrownBy(() -> jiraSettings.addEpic("hello")).isInstanceOf(Invalid.class);
        assertThat(jiraSettings.addEpic("pay-1500").epics()).extracting(JiraSettingsService.EpicView::key).contains("PAY-1500");
        assertThat(jiraSettings.removeEpic("CRM-400").epics()).extracting(JiraSettingsService.EpicView::key).doesNotContain("CRM-400");
    }

    @Test
    void holidaysExtraDaysHoursAlerts() {
        teamSettings.addHoliday("2026-04-30", "Reunification Day", List.of("VN"));
        assertThat(teamSettings.holidays()).anyMatch(h -> h.date().equals("2026-04-30"));
        teamSettings.deleteHoliday("2026-04-30");
        assertThatThrownBy(() -> teamSettings.addExtraDay("2026-10-07", "")).isInstanceOf(Invalid.class)
                .hasMessage("07/10 is already a working day");
        assertThatThrownBy(() -> teamSettings.addExtraDay("2026-10-10", "")).isInstanceOf(Conflict.class);
        teamSettings.addExtraDay("2026-10-17", "Cut-over");
        assertThat(teamSettings.extraDays()).containsEntry("2026-10-17", "Cut-over");
        WorkingHours wh = teamSettings.saveWorkingHours(new WorkingHours(7.5, Arrays.asList(6, 1, 2, 3, 4, 5, 5), 0.5));
        assertThat(wh.days()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThatThrownBy(() -> teamSettings.saveWorkingHours(new WorkingHours(0, List.of(1), 1))).isInstanceOf(Invalid.class);
        Thresholds t = teamSettings.saveAlerts(new Thresholds(3, 80, 3, 3, 100, 1));
        assertThat(t.soonDays()).isEqualTo(3);
        assertThat(teamSettings.alerts().soonDays()).isEqualTo(3);
    }
}
