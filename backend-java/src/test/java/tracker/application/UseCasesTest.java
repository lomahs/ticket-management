package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import tracker.DemoDeps;
import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.usecases.AdminUseCases;
import tracker.application.usecases.AnkenUseCases;
import tracker.application.usecases.AnkenUseCases.FunctionDraft;
import tracker.application.usecases.CalendarUseCases;
import tracker.application.usecases.EffortUseCases;
import tracker.application.usecases.PlanningUseCases;
import tracker.application.usecases.PlanningUseCases.PlanChange;
import tracker.application.usecases.SystemUseCases;
import tracker.application.usecases.TicketUseCases;
import tracker.application.usecases.WorkUseCases;
import tracker.domain.DateReason;
import tracker.domain.LeaveType;
import tracker.domain.Role;
import tracker.domain.StepKind;
import tracker.domain.Thresholds;
import tracker.domain.WorkingHours;

/** The use cases on the demo data, synced through the real pipeline (ported from the original backend's tests). */
class UseCasesTest {

    static List<java.util.Map<String, String>> noOverrides() {
        return new ArrayList<>(Collections.nCopies(5, null));
    }

    static FunctionDraft draft(String parent, String name, double hours) {
        return new FunctionDraft(parent, name, "an", "minh", "tanaka", hours, noOverrides());
    }

    /** Read-only checks share one synced demo database. */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Reads {
        Deps deps;

        @BeforeAll
        void setUp() throws java.io.IOException {
            deps = DemoDeps.demo(java.nio.file.Files.createTempDirectory("tracker-reads"));
        }

        @Test
        void overviewCards() {
            Map<String, AnkenUseCases.AnkenCard> cards = new java.util.LinkedHashMap<>();
            AnkenUseCases.overview(deps).forEach(c -> cards.put(c.key(), c));
            assertThat(cards.keySet()).containsExactly("PAY-1100", "PAY-1300", "PAY-1420", "PAY-0980", "CRM-400", "OPS-200");
            AnkenUseCases.AnkenCard p = cards.get("PAY-1100");
            assertThat(List.of(p.plan(), p.forecast(), p.slipDays(), p.lateFunctions(), p.hasFunctions()))
                    .containsExactly("2026-11-20", "2026-11-25", 3, 6, true);
            assertThat(p.phases()).extracting(x -> x.name()).containsExactly("Prepare", "Implement_Coding", "Function Unit Test");
            assertThat(p.phases().get(1).pct()).isEqualTo(50);
            assertThat(p.phases().get(1).closed()).isEqualTo(25);
            assertThat(p.phases().get(1).total()).isEqualTo(60);
            assertThat(p.memberIds()).containsExactlyInAnyOrder("an", "binh", "chi", "dung", "minh", "tanaka");
            assertThat(cards.get("CRM-400").hasFunctions()).isFalse();
        }

        @Test
        void ankenDetailAttentionGroups() {
            Map<String, List<String>> g = new HashMap<>();
            AnkenUseCases.detail(deps, "PAY-1100").attention().forEach(a -> g.put(a.kind(), a.items().stream().map(TaskCard::key).toList()));
            assertThat(g.get("overdue").subList(0, 3)).containsExactlyInAnyOrder("PAY-1186", "PAY-1219", "PAY-1235");
            assertThat(g.get("stuck_resolved")).containsExactly("PAY-1248");
            assertThat(g.get("many_rounds")).containsExactly("PAY-1213", "PAY-1235");
            assertThat(g.get("waiting_client")).containsExactly("PAY-1219", "PAY-1235");
            assertThatThrownBy(() -> AnkenUseCases.detail(deps, "PAY-404")).isInstanceOf(NotFound.class);
        }

        @Test
        void matrixRowsStatsAndParents() {
            AnkenUseCases.MatrixView m = AnkenUseCases.matrix(deps, "PAY-1100");
            assertThat(m.functions()).extracting(AnkenUseCases.FunctionRow::id)
                    .containsExactly("F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11", "F12");
            AnkenUseCases.MatrixStats s = m.stats();
            assertThat(List.of(s.progressPct(), s.functionsDone(), s.lateFunctions(), s.lateSubtasks(), s.overSubtasks()))
                    .containsExactly(50, 1, 6, 6, 6);
            assertThat(s.loggedH()).isEqualTo(148);
            assertThat(s.estimateH()).isEqualTo(207);
            AnkenUseCases.FunctionRow f03 = m.functions().get(2);
            assertThat(List.of(f03.ownerId(), f03.reviewerId(), f03.current(), f03.state(), f03.progress(), f03.group(), f03.pct()))
                    .containsExactly("binh", "minh", 2, "delay", "late", "W", 48);
            assertThat(f03.cells().get(2).rounds()).isEqualTo(3);
            assertThat(f03.cells().get(2).heldDays()).isEqualTo(2);
            assertThat(f03.cells().get(2).late()).isTrue();
            assertThat(m.phase()).isEqualTo("Implement_Coding");
            assertThat(m.nextFunctionId()).isEqualTo("F13");
            assertThat(m.parents()).extracting(AnkenUseCases.ParentOption::key).containsExactly("PAY-1101", "PAY-1102", "PAY-1103");
        }

        @Test
        void functionPreviewSuggestsDatesAndSummaries() {
            AnkenUseCases.FunctionPreview p = AnkenUseCases.previewFunction(deps, "PAY-1100", draft("PAY-1102", "Installment", 8));
            assertThat(p.phase()).isEqualTo("Implement_Coding");
            assertThat(p.functionId()).isEqualTo("F13");
            assertThat(p.steps()).extracting(s -> s.start() + "/" + s.end()).containsExactly("2026-10-08/2026-10-09",
                    "2026-10-10/2026-10-10", "2026-10-12/2026-10-12", "2026-10-13/2026-10-13", "2026-10-14/2026-10-14");
            assertThat(p.steps().get(0).summary()).isEqualTo("[Implement_Coding][F13] Installment – Create");
            assertThatThrownBy(() -> AnkenUseCases.previewFunction(deps, "PAY-1100", draft("PAY-1102", "X", 0))).isInstanceOf(Invalid.class);
            assertThatThrownBy(() -> AnkenUseCases.previewFunction(deps, "PAY-1100", draft("PAY-1300", "X", 8))).isInstanceOf(Invalid.class);
        }

        @Test
        void timesheetWeek() {
            EffortUseCases.TimesheetView v = EffortUseCases.timesheet(deps, "2026-10-05", "2026-10-11");
            assertThat(v.days()).extracting(PlanningUseCases.DayHead::date).containsExactly("2026-10-05", "2026-10-06", "2026-10-07",
                    "2026-10-08", "2026-10-09", "2026-10-10");
            var binh = v.rows().stream().filter(r -> r.memberId().equals("binh")).findFirst().orElseThrow();
            assertThat(binh.expectedH()).isEqualTo(16);
            assertThat(binh.loggedH()).isGreaterThanOrEqualTo(18.5);
            Map<String, String> chi = new HashMap<>();
            v.rows().stream().filter(r -> r.memberId().equals("chi")).findFirst().orElseThrow().cells().forEach(c -> chi.put(c.date(), c.state()));
            assertThat(chi.get("2026-10-09")).isEqualTo("off");
            assertThat(chi.get("2026-10-08")).isEqualTo("future");
            assertThatThrownBy(() -> EffortUseCases.timesheet(deps, "2026-01-01", "2026-12-31")).isInstanceOf(Invalid.class);
        }

        @Test
        void dayDetailListsWorklogsAndPlannedIssuesWithSummaries() {
            EffortUseCases.DayDetailView d = EffortUseCases.dayDetail(deps, "binh", "2026-10-06");
            var e = d.entries().stream().filter(x -> x.key().equals("PAY-1213")).findFirst().orElseThrow();
            assertThat(e.summary()).endsWith("Fix comment BAL");
            assertThat(d.loggedH()).isEqualTo(tracker.domain.Num.round(
                    tracker.domain.Num.sum(d.entries().stream().mapToDouble(EffortUseCases.DayEntryView::loggedH)), 2));
        }

        @Test
        void effortAnalysisForTheMatrixAnken() {
            EffortUseCases.EffortAnalysis a = EffortUseCases.analysis(deps, null);
            assertThat(a.epicKey()).isEqualTo("PAY-1100");
            assertThat(a.byStep()).extracting(b -> b.label()).containsExactly("Create", "BAL Review", "Fix comment BAL", "JM Review",
                    "Fix comment JM");
            assertThat(a.rework()).extracting(r -> r.memberId()).containsExactly("an", "binh", "chi", "dung");
            assertThat(a.kpis().overSubtasks()).isEqualTo(6);
            assertThat(a.kpis().fixTickets()).isEqualTo(10);
        }

        @Test
        void calendarMonth() {
            CalendarUseCases.CalendarView v = CalendarUseCases.month(deps, "2026-10");
            assertThat(v.days().get(0).date()).isEqualTo("2026-10-01");
            assertThat(v.days()).anyMatch(d -> d.date().equals("2026-10-10"));
            assertThat(v.rows().stream().filter(r -> r.memberId().equals("an")).findFirst().orElseThrow().daysOff()).isEqualTo(3);
            Map<String, CalendarUseCases.CalendarCell> cells = new HashMap<>();
            v.rows().stream().filter(r -> r.memberId().equals("tanaka")).findFirst().orElseThrow().cells().forEach(c -> cells.put(c.date(), c));
            assertThat(cells.get("2026-10-12").kind()).isEqualTo("holiday");
            assertThat(cells.get("2026-10-14").leave().type()).isEqualTo(LeaveType.HALF_TENTATIVE);
        }

        @Test
        void exportRows() {
            List<CalendarUseCases.ExportRow> e = CalendarUseCases.export(deps, "2026-10");
            assertThat(e.get(0)).isEqualTo(new CalendarUseCases.ExportRow("Phạm Quốc Dũng", "2026-10-02", "Half day", ""));
            assertThat(e).contains(new CalendarUseCases.ExportRow("JP", "2026-10-12", "Holiday", "Sports Day (JP)"));
        }

        @Test
        void planningWindowDaysAndMemberDayCells() {
            PlanningUseCases.PlanningView v = PlanningUseCases.view(deps, "2026-10-07", 2);
            assertThat(v.start()).isEqualTo("2026-10-05");
            assertThat(v.end()).isEqualTo("2026-10-18");
            assertThat(v.days()).hasSize(11);
            assertThat(v.days().get(5).extraNote()).isNotNull();
            assertThat(v.days().get(2).isToday()).isTrue();
            Map<String, PlanningUseCases.DayCell> tanaka = new HashMap<>();
            v.rows().stream().filter(r -> r.memberId().equals("tanaka")).findFirst().orElseThrow().days().forEach(c -> tanaka.put(c.date(), c));
            assertThat(tanaka.get("2026-10-12").kind()).isEqualTo("holiday");
            assertThat(tanaka.get("2026-10-14").kind()).isEqualTo("leave_half");
            assertThat(tanaka.get("2026-10-14").tentative()).isTrue();
        }

        @Test
        void planningItemsOverlapTheWindowWithStepColourHints() {
            PlanningUseCases.PlanningView v = PlanningUseCases.view(deps, "2026-10-05", 2);
            Map<String, PlanningUseCases.PlanBlock> binh = new HashMap<>();
            v.rows().stream().filter(r -> r.memberId().equals("binh")).findFirst().orElseThrow().items()
                    .forEach(b -> binh.put(b.card().key(), b));
            assertThat(binh.get("PAY-1213").colorStep()).isEqualTo("fixb");
            assertThat(binh.get("CRM-430").colorStep()).isEqualTo("create");
            assertThat(binh).doesNotContainKey("PAY-1201");
            assertThat(Set.copyOf(v.projects())).isEqualTo(Set.of("PAY", "CRM", "OPS"));
            var cap = v.capacity().stream().filter(c -> c.memberId().equals("chi")).findFirst().orElseThrow().weeks();
            assertThat(cap).hasSize(5);
            assertThat(cap.get(0).availableH()).isEqualTo(40);
        }

        @Test
        void previewMovesItemsAndFlagsDaysOff() {
            PlanningUseCases.PlanningPreview p = PlanningUseCases.preview(deps, "2026-10-05", List.of(new PlanChange("PAY-1262", "2026-10-11", "binh")));
            PlanningUseCases.ChangePreview c = p.changes().get(0);
            assertThat(List.of(c.fromStart(), c.fromEnd(), c.fromMember(), c.toStart(), c.toEnd(), c.toMember()))
                    .containsExactly("2026-10-09", "2026-10-09", "an", "2026-10-11", "2026-10-11", "binh");
            assertThat(c.dayOff()).containsExactly(new DateReason("2026-10-11", "weekend"));
            Map<String, Double> base = new HashMap<>();
            PlanningUseCases.view(deps, "2026-10-05", 1).capacity().forEach(x -> base.put(x.memberId(), x.weeks().get(0).plannedH()));
            Map<String, Double> moved = new HashMap<>();
            p.capacity().forEach(x -> moved.put(x.memberId(), x.weeks().get(0).plannedH()));
            assertThat(moved.get("an")).isEqualTo(base.get("an") - 6);
            assertThat(moved.get("binh")).isEqualTo(base.get("binh") + 6);
            assertThatThrownBy(() -> PlanningUseCases.preview(deps, "2026-10-05", List.of(new PlanChange("PAY-9", "2026-10-11", "binh"))))
                    .isInstanceOf(Invalid.class);
        }

        @Test
        void ticketDetailWithWarningsHistoryAndWorklogs() {
            TicketUseCases.TicketDetail t = TicketUseCases.detail(deps, "PAY-1213");
            assertThat(t.card().functionId()).isEqualTo("F03");
            assertThat(t.ownerId()).isEqualTo("binh");
            assertThat(t.warnings()).containsExactly(new TicketUseCases.TicketWarning("late", 1),
                    new TicketUseCases.TicketWarning("over", 3.5), new TicketUseCases.TicketWarning("rounds", 3));
            assertThat(t.history().get(t.history().size() - 1)).extracting(h -> h.fromId() + ">" + h.toId()).isEqualTo("minh>binh");
            assertThat(t.worklogs().stream().mapToDouble(w -> w.hours()).sum()).isEqualTo(7.5);
            assertThatThrownBy(() -> TicketUseCases.detail(deps, "PAY-9")).isInstanceOf(NotFound.class);
        }

        @Test
        void qaAnswersFromSyncedData() {
            var a = TicketUseCases.ask(deps, "Which functions are late?");
            assertThat(a.text()).startsWith("12 sub-tasks are past their plan, across 12 functions");
            assertThat(a.chips()).hasSize(12).contains("PAY-1213", "PAY-1219", "PAY-1222", "PAY-1226", "PAY-1235", "PAY-1252");
        }

        @Test
        void jiraSettingsMaskTheToken() {
            AdminUseCases.JiraSettingsView s = AdminUseCases.jiraSettings(deps);
            assertThat(s.url()).isEqualTo("https://acme-pay.atlassian.net");
            assertThat(s.tokenMasked()).startsWith("ATATT3xFfGF0").doesNotContain("demo-token");
            assertThat(s.epics()).extracting(AdminUseCases.EpicView::key).startsWith("PAY-1100", "PAY-1300");
            assertThat(s.epics().get(0).title()).isEqualTo("Payment flow revision");
            assertThat(s.lastFullSync()).isNotNull();
        }

        @Test
        void connectionTestUsesUnsavedValues() {
            assertThat(AdminUseCases.testJira(deps, "https://acme-pay.atlassian.net", "x@acme.vn", null)).isEqualTo("ok");
            assertThat(AdminUseCases.testJira(deps, "https://elsewhere.example.com", "x@acme.vn", "short")).isEqualTo("fail");
        }

        @Test
        void fieldMappingCheck() {
            AdminUseCases.FieldMappingView v = AdminUseCases.fieldMapping(deps);
            assertThat(v.plannedStart()).isEqualTo("customfield_10020");
            assertThat(v.effort()).isEqualTo("timeoriginalestimate");
            assertThat(v.catalog()).isNotEmpty();
            Map<String, AdminUseCases.FieldCheck> r = new HashMap<>();
            AdminUseCases.checkFieldMapping(deps, "PAY-1203", Map.of("planned_start", "customfield_10020", "planned_end", "summary", "effort", ""))
                    .results().forEach(x -> r.put(x.target(), x));
            assertThat(r.get("planned_start").status()).isEqualTo("ok");
            assertThat(r.get("planned_start").value()).isEqualTo("2026-09-17");
            assertThat(r.get("planned_end").status()).isEqualTo("type_mismatch");
            assertThat(r.get("effort").status()).isEqualTo("not_mapped");
            assertThatThrownBy(() -> AdminUseCases.checkFieldMapping(deps, "PAY-9", Map.of())).isInstanceOf(NotFound.class);
        }

        @Test
        void statusMappingListsSeenStatuses() {
            Map<String, AdminUseCases.StatusRow> rows = new HashMap<>();
            AdminUseCases.statusMapping(deps).forEach(r -> rows.put(r.name(), r));
            assertThat(rows.get("Resolved").mapped()).isEqualTo("R");
            assertThat(rows.get("In Progress").effective()).isEqualTo("W");
            assertThat(rows.get("Closed").effective()).isEqualTo("C");
        }

        @Test
        void nameRulePreviewAndValidation() {
            AdminUseCases.NameRulePreview p = AdminUseCases.previewNameRule(deps, tracker.domain.Naming.DEFAULT_RULE, null,
                    "[Implement_Coding][F03] Domestic card payment – Fix comment BAL");
            assertThat(p.error()).isNull();
            assertThat(p.step()).isEqualTo(StepKind.FIX_BAL.value());
            assertThat(p.values().get("function_id")).isEqualTo("F03");
            assertThat(p.matched()).isGreaterThanOrEqualTo(60).isLessThan(p.total());
            assertThat(AdminUseCases.previewNameRule(deps, "[{phase}] {step}", null, "x").error()).isEqualTo("Unknown placeholder {phase}");
        }

        @Test
        void metaMembersAndMyWork() {
            SystemUseCases.Meta m = SystemUseCases.meta(deps);
            assertThat(List.of(m.mode(), m.today(), m.capabilities().jiraWrite(), m.sync().status())).containsExactly("demo", "2026-10-07", true, "ok");
            assertThat(m.projects()).containsExactly("CRM", "OPS", "PAY");
            assertThat(SystemUseCases.members(deps)).extracting(x -> x.id()).startsWith("an", "binh");

            WorkUseCases.MyWork w = WorkUseCases.myWork(deps, "binh", "week");
            assertThat(w.nextDay()).isEqualTo("2026-10-08");
            assertThat(w.delay()).extracting(TaskCard::key).containsExactly("PAY-1012", "PAY-1186", "PAY-1184", "PAY-1185", "PAY-1213");
            assertThat(w.dueToday()).extracting(TaskCard::key).containsExactlyInAnyOrder("PAY-1183", "PAY-1187", "PAY-1188", "PAY-1265");
            assertThat(w.dueNext()).extracting(TaskCard::key).contains("PAY-1220");
            assertThat(w.backlog().get(0).kind()).isEqualTo("overdue");
            var weeks = w.backlog().stream().filter(g -> g.kind().equals("week")).toList();
            assertThat(weeks.get(0).start()).isEqualTo("2026-10-05");
            assertThat(weeks.get(0).current()).isTrue();
            assertThat(w.openCount()).isEqualTo(w.backlog().stream().mapToInt(g -> g.items().size()).sum());

            WorkUseCases.MyWork month = WorkUseCases.myWork(deps, "binh", "month");
            assertThat(month.backlog()).extracting(WorkUseCases.BacklogGroup::kind).startsWith("overdue", "month");
            assertThat(month.backlog().get(1).start()).isEqualTo("2026-10");
            WorkUseCases.MyWork minh = WorkUseCases.myWork(deps, "minh", "week");
            assertThat(minh.relatedScope()).isEqualTo("reviewer");
            assertThat(minh.related()).allMatch(c -> !"balr".equals(c.step()));
            assertThatThrownBy(() -> WorkUseCases.myWork(deps, "nobody", "week")).isInstanceOf(NotFound.class);
        }
    }

    // --- writes: a fresh demo database per test --------------------------------------------------------------------

    @TempDir
    Path tmp;

    @Test
    void createFunctionWritesToJiraAndSyncsBack() {
        Deps deps = DemoDeps.demo(tmp);
        FunctionDraft d = draft("PAY-1102", "Installment payment", 8);
        List<String> keys = AnkenUseCases.createFunction(deps, "PAY-1100", d, null);
        assertThat(keys).containsExactly("PAY-1501", "PAY-1502", "PAY-1503", "PAY-1504", "PAY-1505");
        AnkenUseCases.MatrixView m = AnkenUseCases.matrix(deps, "PAY-1100");
        AnkenUseCases.FunctionRow last = m.functions().get(m.functions().size() - 1);
        assertThat(last.id()).isEqualTo("F13");
        assertThat(last.cells()).extracting(TaskCard::key).containsExactlyElementsOf(keys);
        assertThat(m.nextFunctionId()).isEqualTo("F14");
        assertThatThrownBy(() -> AnkenUseCases.createFunction(deps, "PAY-1100", d, "F13")).isInstanceOf(Conflict.class);
    }

    @Test
    void writesNeedJiraWrite() {
        Deps deps = DemoDeps.demo(tmp, false);
        assertThatThrownBy(() -> AnkenUseCases.createFunction(deps, "PAY-1100", draft("PAY-1102", "X", 8), null))
                .isInstanceOf(CapabilityDisabled.class);
        assertThatThrownBy(() -> PlanningUseCases.push(deps, List.of(new PlanChange("PAY-1262", "2026-10-12", "binh"))))
                .isInstanceOf(CapabilityDisabled.class);
        assertThatThrownBy(() -> TicketUseCases.patch(deps, "PAY-1213", new TicketUseCases.TicketPatch("R", null, null, null)))
                .isInstanceOf(CapabilityDisabled.class);
    }

    @Test
    void leaveSaveEditDelete() {
        Deps deps = DemoDeps.demo(tmp);
        int n = CalendarUseCases.saveLeave(deps, new CalendarUseCases.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-02", "2026-11-08", "trip"));
        assertThat(n).isEqualTo(5);
        int id = leaveOn(deps, "2026-11-03").id();
        CalendarUseCases.saveLeave(deps, new CalendarUseCases.LeaveDraft(id, "an", LeaveType.HALF, "2026-11-03", "2026-11-03", "half"));
        tracker.domain.Leave lv = leaveOn(deps, "2026-11-03");
        assertThat(lv.type()).isEqualTo(LeaveType.HALF);
        CalendarUseCases.deleteLeave(deps, lv.id());
        assertThatThrownBy(() -> CalendarUseCases.deleteLeave(deps, lv.id())).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> CalendarUseCases.saveLeave(deps,
                new CalendarUseCases.LeaveDraft(null, "an", LeaveType.FULL, "2026-11-08", "2026-11-02", ""))).isInstanceOf(Invalid.class);
    }

    private static tracker.domain.Leave leaveOn(Deps deps, String date) {
        return CalendarUseCases.month(deps, "2026-11").rows().stream().filter(r -> r.memberId().equals("an"))
                .flatMap(r -> r.cells().stream()).filter(c -> c.date().equals(date)).findFirst().orElseThrow().leave();
    }

    @Test
    void importPreviewAndConfirm() {
        Deps deps = DemoDeps.demo(tmp);
        List<List<String>> rows = List.of(List.of("Bình", "2026-11-10", "2026-11-10", "All day", ""),
                List.of("Duc", "2026-11-13", "2026-11-13", "Half day", ""), List.of("Minh", "2026-11-18", "2026-11-20", "All day", ""));
        CalendarUseCases.ImportPreview p = CalendarUseCases.importPreview(deps, rows);
        assertThat(p.rows()).extracting(r -> r.error()).containsExactly("", "Unknown member \"Duc\"", "");
        assertThat(p.workingDays()).isEqualTo(4);
        assertThat(CalendarUseCases.importConfirm(deps, rows)).isEqualTo(new CalendarUseCases.ImportResult(4, "2026-11"));
    }

    @Test
    void pushWritesDatesAndAssigneeThenSyncs() {
        Deps deps = DemoDeps.demo(tmp);
        PlanningUseCases.push(deps, List.of(new PlanChange("PAY-1262", "2026-10-12", "binh")));
        TaskCard c = PlanningUseCases.view(deps, "2026-10-12", 1).rows().stream().filter(r -> r.memberId().equals("binh"))
                .flatMap(r -> r.items().stream()).map(PlanningUseCases.PlanBlock::card).filter(x -> x.key().equals("PAY-1262"))
                .findFirst().orElseThrow();
        assertThat(List.of(c.plannedStart(), c.plannedEnd(), c.assigneeId())).containsExactly("2026-10-12", "2026-10-12", "binh");
    }

    @Test
    void patchTicketWritesAndSyncs() {
        Deps d = DemoDeps.demo(tmp);
        TicketUseCases.patch(d, "PAY-1213", new TicketUseCases.TicketPatch("R", "minh", null, "2026-10-08"));
        TaskCard c = TicketUseCases.detail(d, "PAY-1213").card();
        assertThat(List.of(c.status(), c.assigneeId(), c.plannedEnd(), c.late())).containsExactly("R", "minh", "2026-10-08", false);
        assertThatThrownBy(() -> TicketUseCases.patch(d, "PAY-1213", new TicketUseCases.TicketPatch(null, null, "2026-10-20", null)))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void saveJiraKeepsTokenWhenBlankAndValidatesInterval() {
        Deps d = DemoDeps.demo(tmp);
        AdminUseCases.JiraSettingsView s = AdminUseCases.saveJira(d, "https://acme-pay.atlassian.net", "new@acme.vn", null, "60");
        assertThat(List.of(s.email(), s.interval(), s.status())).containsExactly("new@acme.vn", "60", "ok");
        assertThatThrownBy(() -> AdminUseCases.saveJira(d, "https://acme-pay.atlassian.net", "new@acme.vn", null, "7"))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void addAndRemoveEpic() {
        Deps d = DemoDeps.demo(tmp);
        assertThatThrownBy(() -> AdminUseCases.addEpic(d, "pay-1100")).isInstanceOf(Conflict.class);
        assertThatThrownBy(() -> AdminUseCases.addEpic(d, "PAY-1201")).isInstanceOf(NotFound.class)
                .hasMessageContaining("was not found or is not an Epic");
        assertThatThrownBy(() -> AdminUseCases.addEpic(d, "hello")).isInstanceOf(Invalid.class);
        assertThat(AdminUseCases.addEpic(d, "pay-1500").epics()).extracting(AdminUseCases.EpicView::key).contains("PAY-1500");
        assertThat(AdminUseCases.removeEpic(d, "CRM-400").epics()).extracting(AdminUseCases.EpicView::key).doesNotContain("CRM-400");
    }

    @Test
    void memberUpdate() {
        Deps d = DemoDeps.demo(tmp);
        tracker.domain.Member m = AdminUseCases.updateMember(d, "an", new AdminUseCases.MemberPatch("Ann", Role.BAL, null, "JP", false));
        assertThat(List.of(m.role(), m.country(), m.name())).containsExactly(Role.BAL, "JP", "Ann");
        assertThatThrownBy(() -> AdminUseCases.updateMember(d, "zz", new AdminUseCases.MemberPatch(null, null, null, null, false)))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void holidaysExtraDaysHoursAlerts() {
        Deps d = DemoDeps.demo(tmp);
        AdminUseCases.addHoliday(d, "2026-04-30", "Reunification Day", List.of("VN"));
        assertThat(AdminUseCases.holidays(d)).anyMatch(h -> h.date().equals("2026-04-30"));
        AdminUseCases.deleteHoliday(d, "2026-04-30");
        assertThatThrownBy(() -> AdminUseCases.addExtraDay(d, "2026-10-07", "")).isInstanceOf(Invalid.class)
                .hasMessage("07/10 is already a working day");
        assertThatThrownBy(() -> AdminUseCases.addExtraDay(d, "2026-10-10", "")).isInstanceOf(Conflict.class);
        AdminUseCases.addExtraDay(d, "2026-10-17", "Cut-over");
        assertThat(AdminUseCases.extraDays(d)).containsEntry("2026-10-17", "Cut-over");
        WorkingHours wh = AdminUseCases.saveWorkingHours(d, new WorkingHours(7.5, Arrays.asList(6, 1, 2, 3, 4, 5, 5), 0.5));
        assertThat(wh.days()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThatThrownBy(() -> AdminUseCases.saveWorkingHours(d, new WorkingHours(0, List.of(1), 1))).isInstanceOf(Invalid.class);
        Thresholds t = AdminUseCases.saveAlerts(d, new Thresholds(3, 80, 3, 3, 100));
        assertThat(t.soonDays()).isEqualTo(3);
        assertThat(AdminUseCases.alerts(d).soonDays()).isEqualTo(3);
    }
}
