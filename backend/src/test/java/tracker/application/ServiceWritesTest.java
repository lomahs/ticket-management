package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
import tracker.domain.LeaveType;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;

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
        AnkenService.MatrixView m = anken.matrix("PAY-1100");
        AnkenService.FunctionRow last = m.functions().get(m.functions().size() - 1);
        assertThat(last.id()).isEqualTo("F13");
        assertThat(last.cells()).extracting(TaskCard::key).containsExactlyElementsOf(keys);
        assertThat(m.nextFunctionId()).isEqualTo("F14");
        assertThatThrownBy(() -> anken.createFunction("PAY-1100", d, "F13")).isInstanceOf(Conflict.class);
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
    void memberUpdate() {
        tracker.domain.Member m = teamSettings.updateMember("an", new TeamSettingsService.MemberPatch("Ann", Role.BAL, null, "JP", false));
        assertThat(List.of(m.role(), m.country(), m.name())).containsExactly(Role.BAL, "JP", "Ann");
        assertThatThrownBy(() -> teamSettings.updateMember("zz", new TeamSettingsService.MemberPatch(null, null, null, null, false)))
                .isInstanceOf(NotFound.class);
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
