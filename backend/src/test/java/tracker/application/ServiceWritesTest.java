package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.MemberRepository;
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
import tracker.application.service.WorkService;
import tracker.application.settings.SettingsStore;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.LeaveType;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.TaskTemplate;
import tracker.domain.Thresholds;
import tracker.domain.Worklog;
import tracker.domain.WorkingHours;
import tracker.domain.WorkStructure;

/** Services that write: each test starts from fresh demo data. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ServiceWritesTest {
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
        List<String> keys = anken.createFunction("PAY-1100", d, null);
        assertThat(keys).containsExactly("PAY-1501", "PAY-1502", "PAY-1503", "PAY-1504", "PAY-1505");
        AnkenService.MatrixView m = anken.matrix("PAY-1100", null, null, null);
        AnkenService.FunctionRow last = m.functions().get(m.functions().size() - 1);
        assertThat(last.id()).isEqualTo("F13");
        assertThat(last.cells()).extracting(TaskCard::key).containsExactlyElementsOf(keys);
        assertThat(m.nextFunctionId()).isEqualTo("F14");
        assertThatThrownBy(() -> anken.createFunction("PAY-1100", d, "F13")).isInstanceOf(Conflict.class);
    }

    /** The company's naming: Tasks recognised by name, sub-tasks named [prefix][function name]_Step without a function ID. */
    @Test
    void createFunctionWithCompanyNaming(@Autowired SettingsStore settings, @Autowired SyncService sync) {
        settings.update(s -> {
            s.nameRules.rule = "[{prefix}][{function_name}]_{step}";
            s.nameRules.aliases = new java.util.EnumMap<>(Map.of(StepKind.CREATE, "Create", StepKind.BAL_REVIEW, "BALReview",
                    StepKind.FIX_BAL, "FixCommentBAL", StepKind.JM_REVIEW, "JMReview", StepKind.FIX_JM, "FixCommentJM"));
            s.structure = new WorkStructure(List.of("Prepare", "Implement_Coding", "Function Unit Test"),
                    List.of(new TaskTemplate("[Implement_Coding] *", "Implement_Coding", "UCI"),
                            new TaskTemplate("[Prepare] *", "Prepare", "PRE", false)));
        });
        sync.run(true);
        FunctionDraft d = draft("PAY-1102", "Login", 8);
        AnkenService.FunctionPreview p = anken.previewFunction("PAY-1100", d);
        assertThat(List.of(p.phase(), p.prefix(), p.functionId())).containsExactly("Implement_Coding", "UCI", "");
        assertThat(p.steps()).extracting(AnkenService.PreviewStep::summary).containsExactly("[UCI][Login]_Create",
                "[UCI][Login]_BALReview", "[UCI][Login]_FixCommentBAL", "[UCI][Login]_JMReview", "[UCI][Login]_FixCommentJM");
        List<String> keys = anken.createFunction("PAY-1100", d, null);
        AnkenService.MatrixView m = anken.matrix("PAY-1100", List.of("PAY-1102"), null, null); // the demo's own functions no longer match the rule
        assertThat(m.functions()).extracting(f -> f.id() + "/" + f.name()).containsExactly("Login/Login");
        assertThat(m.functions().get(0).cells()).extracting(TaskCard::key).containsExactlyElementsOf(keys);
        assertThatThrownBy(() -> anken.createFunction("PAY-1100", d, null)).isInstanceOf(Conflict.class); // same name, same Task
        assertThatThrownBy(() -> anken.previewFunction("PAY-1100", draft("PAY-1101", "X", 8))).isInstanceOf(Invalid.class);
    }

    @Test
    void saveStructureResyncsPhases() {
        jiraSettings.saveStructure(new WorkStructure(List.of("Coding", "Prepare"),
                List.of(new TaskTemplate("[Implement_Coding] *", "Coding", "UCI"))));
        AnkenService.AnkenCard card = anken.overview().stream().filter(c -> c.key().equals("PAY-1100")).findFirst().orElseThrow();
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
        List<AnkenService.AnkenCard> cards = anken.overview().stream().filter(c -> c.key().equals("CRM-400")).toList();
        var phases = cards.get(0).phases();
        assertThat(phases).extracting(p -> p.name()).containsExactly("Implement_Coding", "ITA", "Other");
        assertThat(List.of(phases.get(1).pct(), phases.get(1).closed(), phases.get(1).total())).containsExactly(50, 1, 2);
        assertThat(List.of(phases.get(1).start(), phases.get(1).end())).containsExactly("2026-10-01", "2026-10-09");
        assertThat(List.of(phases.get(2).pct(), phases.get(2).total())).containsExactly(0, 0);
    }

    /** Without a JiraWriter (the Jira profile in phase 1) nothing is written to Jira. */
    @Test
    void writesNeedJiraWrite(@Autowired TrackerContextLoader context, @Autowired IssueRepository issues,
                             @Autowired MemberRepository members, @Autowired SyncService sync) {
        assertThatThrownBy(() -> new AnkenService(context, Optional.empty(), sync).createFunction("PAY-1100", draft("PAY-1102", "X", 8), null))
                .isInstanceOf(CapabilityDisabled.class);
        assertThatThrownBy(() -> new PlanningService(context, Optional.empty(), sync)
                .push(List.of(new PlanChange("PAY-1262", "2026-10-12", "binh")))).isInstanceOf(CapabilityDisabled.class);
        assertThatThrownBy(() -> new TicketService(context, issues, members, Optional.empty(), sync)
                .patch("PAY-1213", new TicketService.TicketPatch(Status.RESOLVED, null, null, null))).isInstanceOf(CapabilityDisabled.class);
    }

    @Test
    void leaveSaveEditDelete() {
        int n = calendar.saveLeave(new CalendarService.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-02", "2026-11-08", "trip"));
        assertThat(n).isEqualTo(5);
        int id = leaveOn("2026-11-03").id();
        calendar.saveLeave(new CalendarService.LeaveDraft(id, "an", LeaveType.HALF, "2026-11-03", "2026-11-03", "half"));
        tracker.domain.Leave lv = leaveOn("2026-11-03");
        assertThat(lv.type()).isEqualTo(LeaveType.HALF);
        calendar.deleteLeave(lv.id());
        assertThatThrownBy(() -> calendar.deleteLeave(lv.id())).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> calendar.saveLeave(
                new CalendarService.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-08", "2026-11-02", ""))).isInstanceOf(Invalid.class);
    }

    private tracker.domain.Leave leaveOn(String date) {
        return calendar.month("2026-11").rows().stream().filter(r -> r.memberId().equals("an"))
                .flatMap(r -> r.cells().stream()).filter(c -> c.date().equals(date)).findFirst().orElseThrow().leave();
    }

    @Test
    void importPreviewAndConfirm() {
        List<List<String>> rows = List.of(List.of("Bình", "2026-11-10", "2026-11-10", "All day", ""),
                List.of("Duc", "2026-11-13", "2026-11-13", "Half day", ""), List.of("Minh", "2026-11-18", "2026-11-20", "All day", ""));
        CalendarService.ImportPreview p = calendar.importPreview(rows);
        assertThat(p.rows()).extracting(r -> r.error()).containsExactly("", "Unknown member \"Duc\"", "");
        assertThat(p.workingDays()).isEqualTo(4);
        assertThat(calendar.importConfirm(rows)).isEqualTo(new CalendarService.ImportResult(4, "2026-11"));
    }

    @Test
    void pushWritesDatesAndAssigneeThenSyncs() {
        planning.push(List.of(new PlanChange("PAY-1262", "2026-10-12", "binh")));
        TaskCard c = planning.view("2026-10-12", 1).rows().stream().filter(r -> r.memberId().equals("binh"))
                .flatMap(r -> r.items().stream()).map(PlanningService.PlanBlock::card).filter(x -> x.key().equals("PAY-1262"))
                .findFirst().orElseThrow();
        assertThat(List.of(c.plannedStart(), c.plannedEnd(), c.assigneeId())).containsExactly("2026-10-12", "2026-10-12", "binh");
    }

    @Test
    void patchTicketWritesAndSyncs() {
        tickets.patch("PAY-1213", new TicketService.TicketPatch(Status.RESOLVED, "minh", null, "2026-10-08"));
        TaskCard c = tickets.detail("PAY-1213").card();
        assertThat(List.of(c.status(), c.assigneeId(), c.plannedEnd(), c.late())).containsExactly("R", "minh", "2026-10-08", false);
        assertThatThrownBy(() -> tickets.patch("PAY-1213", new TicketService.TicketPatch(null, null, "2026-10-20", null)))
                .isInstanceOf(Invalid.class);
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
        Thresholds t = teamSettings.saveAlerts(new Thresholds(3, 80, 3, 3, 100));
        assertThat(t.soonDays()).isEqualTo(3);
        assertThat(teamSettings.alerts().soonDays()).isEqualTo(3);
    }
}
