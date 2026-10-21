package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.adapters.fake.FakeJira;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraReaderFactory;
import tracker.application.ports.SecretBox;
import tracker.application.ports.SyncRunRepository;
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
import tracker.domain.Allocation;
import tracker.domain.AnkenScope;
import tracker.domain.Burndown;
import tracker.domain.DateReason;
import tracker.domain.Issue;
import tracker.domain.LeaveType;
import tracker.domain.Numbers;
import tracker.domain.StepKind;
import tracker.domain.TaskTemplate;
import tracker.domain.WorkStructure;

/** The services on the demo data, synced through the real pipeline; read-only checks share one context. */
@DemoTest
class ServiceReadsTest {
    /** Every tracked epic of the demo: the scope of a viewer who sees all anken. */
    static final AnkenScope ALL = new AnkenScope(Set.copyOf(Dataset.TRACKED_EPICS));

    @Autowired
    AnkenService anken;
    @Autowired
    PlanningService planning;
    @Autowired
    EffortService effort;
    @Autowired
    AllocationRepository allocations;
    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;
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

    @Autowired
    TrackerContextLoader contexts;

    @Test
    void cardCarriesTheEpicRollup() {
        TrackerContext ctx = contexts.load(ALL);
        for (AnkenService.AnkenCard c : anken.overview(ALL)) {
            long overdue = ctx.subtasks().stream().filter(i -> i.epicKey().equals(c.key()))
                    .filter(i -> ctx.schedule.get(i.key()).finish() == tracker.domain.FinishState.OVERDUE).count();
            assertThat(c.schedule().overdue()).as(c.key()).isEqualTo((int) overdue);
            assertThat(c.phases().stream().mapToInt(p -> p.schedule().overdue()).sum()).as(c.key()).isEqualTo((int) overdue);
        }
        assertThat(anken.overview(ALL).get(0).schedule().overdue()).isPositive();
    }

    @Test
    void overviewCards() {
        Map<String, AnkenService.AnkenCard> cards = new java.util.LinkedHashMap<>();
        anken.overview(ALL).forEach(c -> cards.put(c.key(), c));
        assertThat(cards.keySet()).containsExactly("PAY-1100", "PAY-1300", "PAY-1420", "PAY-0980", "CRM-400", "OPS-200");
        AnkenService.AnkenCard p = cards.get("PAY-1100");
        assertThat(List.of(p.plan(), p.forecast(), p.slipDays(), p.lateFunctions(), p.hasFunctions()))
                .containsExactly("2026-11-20", "2026-11-25", 3, 6, true);
        assertThat(p.phases()).extracting(x -> x.name()).containsExactly("Prepare", "Implement_Coding", "Function Unit Test");
        assertThat(p.phases().get(1).pct()).isEqualTo(42); // logged / (logged + remaining)
        assertThat(p.phases().get(1).closed()).isEqualTo(25);
        assertThat(p.phases().get(1).total()).isEqualTo(60);
        assertThat(p.memberIds()).containsExactlyInAnyOrder("an", "binh", "chi", "dung", "minh", "tanaka");
        assertThat(cards.get("CRM-400").hasFunctions()).isFalse();
    }

    @Test
    void matrixPhaseShowsEveryTaskOfThePhaseAndKeepsAllTasksAsOptions() {
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", null, "Implement_Coding", null);
        assertThat(m.parents()).extracting(AnkenService.ParentOption::key).containsExactly("PAY-1101", "PAY-1102", "PAY-1103");
        assertThat(m.taskKeys()).containsExactly("PAY-1102");
        assertThat(m.phase()).isEqualTo("Implement_Coding");
        assertThat(m.functions()).isNotEmpty().allMatch(r -> r.taskKey().equals("PAY-1102"));
    }

    @Test
    void matrixPhaseWithoutFunctionsIsEmptyNotMissing() {
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", null, "Prepare", null);
        assertThat(List.of(m.taskKeys(), m.phase())).containsExactly(List.of("PAY-1101"), "Prepare");
        assertThat(m.functions()).isEmpty();
    }

    @Test
    void matrixShowsTheChosenTasksOfSeveralPhases() {
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", List.of("PAY-1103", "PAY-1102"), "Prepare", null);
        assertThat(m.taskKeys()).containsExactly("PAY-1102", "PAY-1103"); // in Task order; the phase is ignored
        assertThat(m.phase()).isEqualTo("Implement_Coding, Function Unit Test");
        assertThat(m.stats().functionsTotal()).isEqualTo(m.functions().size());
    }

    @Test
    void matrixShowsTheViewersOwnTasksSkippingOnesThatAreGone() {
        assertThat(anken.matrix(ALL, "PAY-1100", null, null, List.of("PAY-1103", "PAY-1101", "PAY-9999")).taskKeys())
                .containsExactly("PAY-1101", "PAY-1103");
        assertThat(anken.matrix(ALL, "PAY-1100", null, null, List.of("PAY-9999")).taskKeys()).containsExactly("PAY-1102"); // none left: the default
        assertThat(anken.matrix(ALL, "PAY-1100", null, "Prepare", List.of("PAY-1103")).taskKeys()).containsExactly("PAY-1101"); // a link wins
    }

    @Test
    void matrixPhaseAndTasksMustExist() {
        assertThatThrownBy(() -> anken.matrix(ALL, "PAY-1100", null, "Nope", null)).isInstanceOf(NotFound.class);
        assertThatThrownBy(() -> anken.matrix(ALL, "PAY-1100", List.of("PAY-1102", "PAY-1301"), null, null)).isInstanceOf(NotFound.class);
    }

    @Test
    void ankenDetailAttentionGroups() {
        Map<String, List<String>> g = new HashMap<>();
        anken.detail(ALL, "PAY-1100").attention().forEach(a -> g.put(a.kind(), a.items().stream().map(TaskCard::key).toList()));
        assertThat(g.get("overdue").subList(0, 3)).containsExactlyInAnyOrder("PAY-1186", "PAY-1219", "PAY-1235");
        assertThat(g.get("stuck_resolved")).containsExactly("PAY-1248");
        assertThat(g.get("many_rounds")).containsExactly("PAY-1213", "PAY-1235");
        assertThat(g.get("waiting_client")).containsExactly("PAY-1219", "PAY-1235");
        assertThatThrownBy(() -> anken.detail(ALL, "PAY-404")).isInstanceOf(NotFound.class);
    }

    @Test
    void ankenDetailListsTasksUnderTheirPhase() {
        AnkenService.AnkenDetail d = anken.detail(ALL, "PAY-1100");
        assertThat(d.tasks()).extracting(t -> t.key() + "/" + t.phase())
                .containsExactlyInAnyOrder("PAY-1101/Prepare", "PAY-1102/Implement_Coding", "PAY-1103/Function Unit Test");
        for (var p : d.card().phases()) {
            var tasks = d.tasks().stream().filter(t -> t.phase().equals(p.name())).toList();
            assertThat(tasks.stream().mapToInt(AnkenService.PhaseTask::total).sum()).as(p.name()).isEqualTo(p.total());
            assertThat(tasks.stream().mapToInt(AnkenService.PhaseTask::closed).sum()).as(p.name()).isEqualTo(p.closed());
        }
    }

    @Test
    void matrixRowsStatsAndParents() {
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", null, null, null);
        assertThat(m.functions()).extracting(AnkenService.FunctionRow::id)
                .containsExactly("F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11", "F12");
        AnkenService.MatrixStats s = m.stats();
        assertThat(List.of(s.progressPct(), s.functionsDone(), s.lateFunctions(), s.lateSubtasks(), s.overSubtasks()))
                .containsExactly(50, 1, 6, 6, 6);
        assertThat(s.loggedH()).isEqualTo(148);
        assertThat(s.estimateH()).isEqualTo(207);
        AnkenService.FunctionRow f03 = m.functions().get(2);
        assertThat(List.of(f03.ownerId(), f03.reviewerId(), f03.current(), f03.state(), f03.progress(), f03.group(), f03.pct()))
                .containsExactly("binh", "minh", 2, "delay", "late", "W", 48);
        assertThat(f03.cells().get(2).rounds()).isEqualTo(3);
        assertThat(f03.cells().get(2).heldDays()).isEqualTo(2);
        assertThat(f03.cells().get(2).late()).isTrue();
        assertThat(m.phase()).isEqualTo("Implement_Coding");
        assertThat(m.nextFunctionId()).isEqualTo("F13");
        assertThat(m.parents()).extracting(AnkenService.ParentOption::key).containsExactly("PAY-1101", "PAY-1102", "PAY-1103");
    }

    @Test
    void functionOfASubtaskHasItsFiveSteps() {
        AnkenService.FunctionRow row = anken.matrix(ALL, "PAY-1100", null, null, null).functions().get(2);
        AnkenService.FunctionSteps f = anken.functionOf(ALL, row.cells().get(2).key());
        assertThat(List.of(f.epicKey(), f.taskKey(), f.phase(), f.functionId(), f.name()))
                .containsExactly("PAY-1100", row.taskKey(), "Implement_Coding", "F03", row.name());
        assertThat(f.steps()).extracting(c -> c == null ? null : c.key())
                .containsExactlyElementsOf(row.cells().stream().map(c -> c == null ? null : c.key()).toList());
        assertThatThrownBy(() -> anken.functionOf(ALL, "PAY-1101")).isInstanceOf(NotFound.class); // a Task, not a step
    }

    @Test
    void matrixDefaultsToTheFirstPhaseWithFunctions(@Autowired IssueRepository issues) {
        AnkenService.MatrixView m = anken.matrix(ALL, "PAY-1100", null, null, null);
        assertThat(m.taskKeys()).containsExactly("PAY-1102");
        assertThat(m.parents()).extracting(p -> p.key() + "/" + p.group() + "/" + p.functions() + "/" + p.prefix() + "/" + p.workflow())
                .containsExactly("PAY-1101/Prepare/0/Prepare/true", "PAY-1102/Implement_Coding/12/Implement_Coding/true",
                        "PAY-1103/Function Unit Test/0/Function Unit Test/true");
        AnkenService.ParentOption coding = m.parents().get(1);
        List<Issue> subs = issues.findAll().stream().filter(i -> "PAY-1102".equals(i.parentKey())).toList();
        assertThat(coding.start()).isEqualTo(subs.stream().map(Issue::plannedStart).filter(Objects::nonNull).min(String::compareTo).orElseThrow());
        assertThat(coding.end()).isEqualTo(subs.stream().map(Issue::plannedEnd).filter(Objects::nonNull).max(String::compareTo).orElseThrow());
        assertThat(anken.matrix(ALL, "PAY-1100", List.of("PAY-1101"), null, null).functions()).isEmpty();
        assertThatThrownBy(() -> anken.matrix(ALL, "PAY-1100", List.of("PAY-1301"), null, null)).isInstanceOf(NotFound.class); // a Task of another epic
    }

    @Test
    void functionPreviewSuggestsDatesAndSummaries() {
        AnkenService.FunctionPreview p = anken.previewFunction(ALL, "PAY-1100", draft("PAY-1102", "Installment", 8));
        assertThat(p.phase()).isEqualTo("Implement_Coding");
        assertThat(p.functionId()).isEqualTo("F13");
        assertThat(p.steps()).extracting(s -> s.start() + "/" + s.end()).containsExactly("2026-10-08/2026-10-09",
                "2026-10-10/2026-10-10", "2026-10-12/2026-10-12", "2026-10-13/2026-10-13", "2026-10-14/2026-10-14");
        assertThat(p.steps().get(0).summary()).isEqualTo("[Implement_Coding][F13] Installment – Create");
        assertThatThrownBy(() -> anken.previewFunction(ALL, "PAY-1100", draft("PAY-1102", "X", 0))).isInstanceOf(Invalid.class);
        assertThatThrownBy(() -> anken.previewFunction(ALL, "PAY-1100", draft("PAY-1300", "X", 8))).isInstanceOf(Invalid.class);
    }

    @Test
    void functionPreviewUsesTheEpicsAliases(@Autowired SettingsStore store) {
        String other = anken.matrix(ALL, "PAY-1300", null, null, null).parents().stream().filter(AnkenService.ParentOption::workflow)
                .findFirst().orElseThrow().key();
        Map<StepKind, String> before = new java.util.LinkedHashMap<>(store.load().nameRulesOf("PAY-1100").aliases);
        try {
            store.update(s -> {
                s.anken.computeIfAbsent("PAY-1100", e -> tracker.application.settings.TrackerSettings.AnkenSettings.copyOf(s.structure, s.nameRules));
                s.anken.get("PAY-1100").nameRules.aliases.put(StepKind.CREATE, "Tao");
            });
            assertThat(anken.previewFunction(ALL, "PAY-1100", draft("PAY-1102", "Installment", 8)).steps().get(0).summary())
                    .contains("Tao");
            assertThat(anken.previewFunction(ALL, "PAY-1300", draft(other, "Installment", 8)).steps().get(0).summary())
                    .doesNotContain("Tao");
        } finally {
            store.update(s -> s.anken.get("PAY-1100").nameRules.aliases = before);
        }
    }

    @Test
    void timesheetWeek() {
        EffortService.TimesheetView v = effort.timesheet(ALL, "2026-10-05", "2026-10-11");
        assertThat(v.days()).extracting(PlanningService.DayHead::date).containsExactly("2026-10-05", "2026-10-06", "2026-10-07",
                "2026-10-08", "2026-10-09", "2026-10-10");
        var binh = v.rows().stream().filter(r -> r.memberId().equals("binh")).findFirst().orElseThrow();
        assertThat(binh.targetH()).isEqualTo(16); // the demo gives Bình 8h a day (2 days counted before today)
        assertThat(binh.loggedH()).isGreaterThanOrEqualTo(18.5);
        Map<String, String> chi = new HashMap<>();
        v.rows().stream().filter(r -> r.memberId().equals("chi")).findFirst().orElseThrow().cells().forEach(c -> chi.put(c.date(), c.state()));
        assertThat(chi.get("2026-10-09")).isEqualTo("off");
        assertThat(chi.get("2026-10-08")).isEqualTo("future");
        assertThatThrownBy(() -> effort.timesheet(ALL, "2026-01-01", "2026-12-31")).isInstanceOf(Invalid.class);
    }

    @Test
    void timesheetUsesAllocation() {
        var before = allocations.findByMember("an");
        jdbc.sql("DELETE FROM allocations WHERE member_id = 'an'").update();
        allocations.save(new Allocation("an", "PAY-1100", "2026-09-01", 8), "minh", "2026-10-07T09:00:00Z");
        try {
            var an = effort.timesheet(new AnkenScope(Set.of("PAY-1100")), "2026-10-05", "2026-10-11").rows().stream()
                    .filter(r -> r.memberId().equals("an")).findFirst().orElseThrow();
            var monday = an.cells().stream().filter(c -> c.date().equals("2026-10-05")).findFirst().orElseThrow();
            assertThat(monday.targetH()).isEqualTo(8);
        } finally {
            jdbc.sql("DELETE FROM allocations WHERE member_id = 'an'").update();
            before.forEach(a -> allocations.save(a, "seed", "2026-10-07T09:00:00Z"));
        }
    }

    /**
     * The demo gives an 5h on PAY-1100 and 3h on PAY-1300; once an leaves PAY-1300 that row is history: neither the timesheet
     * target nor the capacity of the scope of every anken counts it. Restores an's rows and membership exactly.
     */
    @Test
    void allocationOfAnAnkenTheMemberLeftDoesNotCount() {
        var rows = allocations.findStampedByMember("an");
        var membership = ankenMembers.findByEpic("PAY-1300").stream().filter(m -> m.memberId().equals("an")).findFirst().orElseThrow();
        allocations.save(new Allocation("an", "PAY-1100", "2026-09-01", 8), "minh", "2026-10-07T09:00:00Z");
        ankenMembers.remove("PAY-1300", "an");
        try {
            var monday = effort.timesheet(ALL, "2026-10-05", "2026-10-11").rows().stream().filter(r -> r.memberId().equals("an"))
                    .findFirst().orElseThrow().cells().stream().filter(c -> c.date().equals("2026-10-05")).findFirst().orElseThrow();
            assertThat(monday.targetH()).isEqualTo(8); // not 8 + 3
            var everything = planning.view(ALL, "2026-10-05", 1).capacity().stream().filter(c -> c.memberId().equals("an"))
                    .findFirst().orElseThrow().weeks().get(0);
            var pay1100 = planning.view(new AnkenScope(Set.of("PAY-1100")), "2026-10-05", 1).capacity().stream()
                    .filter(c -> c.memberId().equals("an")).findFirst().orElseThrow().weeks().get(0);
            assertThat(everything.availableH()).isPositive().isEqualTo(pay1100.availableH());
        } finally {
            ankenMembers.add("PAY-1300", "an", membership.addedBy(), membership.addedAt());
            jdbc.sql("DELETE FROM allocations WHERE member_id = 'an'").update();
            rows.forEach(r -> allocations.save(r.allocation(), r.setBy(), r.setAt()));
        }
    }

    @Test
    void dayDetailListsWorklogsAndPlannedIssuesWithSummaries() {
        EffortService.DayDetailView d = effort.dayDetail(ALL, "binh", "2026-10-06");
        var e = d.entries().stream().filter(x -> x.key().equals("PAY-1213")).findFirst().orElseThrow();
        assertThat(e.summary()).endsWith("Fix comment BAL");
        assertThat(d.loggedH()).isEqualTo(Numbers.round(d.entries().stream().mapToDouble(EffortService.DayEntryView::loggedH).sum(), 2));
    }

    @Test
    void effortAnalysisForTheMatrixAnken() {
        EffortService.EffortAnalysis a = effort.analysis(ALL, null);
        assertThat(a.epicKey()).isEqualTo("PAY-1100");
        assertThat(a.byStep()).extracting(b -> b.label()).containsExactly("Create", "BAL Review", "Fix comment BAL", "JM Review",
                "Fix comment JM");
        assertThat(a.rework()).extracting(r -> r.memberId()).containsExactly("an", "binh", "chi", "dung");
        assertThat(a.kpis().overSubtasks()).isEqualTo(6);
        assertThat(a.kpis().fixTickets()).isEqualTo(10);
    }

    @Test
    void burndownForTheMatrixAnkenFiltersByAnkenPhaseSubPhaseAndMember() {
        EffortService.BurndownView all = effort.burndown(ALL, null, null, null, null);
        assertThat(all.epicKey()).isEqualTo("PAY-1100");
        assertThat(all.ankens()).extracting(EffortService.BurndownOption::key).contains("PAY-1100");
        assertThat(all.phases()).isNotEmpty();
        assertThat(all.tasks()).isEmpty();
        assertThat(all.subtasks()).isPositive();
        Burndown.BurndownChart b = all.burndown();
        assertThat(b.days().get(0).planRemainingH()).isEqualTo(b.estimateH()); // the baseline, the day before the anken starts
        assertThat(b.days().get(b.days().size() - 1).planRemainingH()).isZero();
        assertThat(b.days()).allMatch(d -> d.date().compareTo(all.today()) <= 0 == (d.actualRemainingH() != null));
        String phase = all.phases().get(0);
        EffortService.BurndownView inPhase = effort.burndown(ALL, "PAY-1100", phase, null, null);
        assertThat(inPhase.tasks()).isNotEmpty();
        EffortService.BurndownView sub = effort.burndown(ALL, "PAY-1100", phase, inPhase.tasks().get(0).key(), null);
        assertThat(sub.task()).isEqualTo(inPhase.tasks().get(0).key());
        assertThat(sub.subtasks()).isPositive().isLessThanOrEqualTo(inPhase.subtasks());
        EffortService.BurndownView one = effort.burndown(ALL, "PAY-1100", phase, null, inPhase.members().get(0));
        assertThat(one.phase()).isEqualTo(phase);
        assertThat(one.subtasks()).isLessThan(all.subtasks());
        assertThat(one.burndown().estimateH()).isLessThan(b.estimateH());
        assertThat(one.burndown().start()).isEqualTo(inPhase.burndown().start()); // the phase's start, whoever is picked
        assertThatThrownBy(() -> effort.burndown(ALL, "NOPE-1", null, null, null)).isInstanceOf(NotFound.class);
    }

    @Test
    void calendarMonth() {
        CalendarService.CalendarView v = calendar.month(ALL, "2026-10", null);
        assertThat(v.days().get(0).date()).isEqualTo("2026-10-01");
        assertThat(v.days()).anyMatch(d -> d.date().equals("2026-10-10"));
        assertThat(v.rows().stream().filter(r -> r.memberId().equals("an")).findFirst().orElseThrow().daysOff()).isEqualTo(3);
        Map<String, CalendarService.CalendarCell> cells = new HashMap<>();
        v.rows().stream().filter(r -> r.memberId().equals("tanaka")).findFirst().orElseThrow().cells().forEach(c -> cells.put(c.date(), c));
        assertThat(cells.get("2026-10-12").kind()).isEqualTo("holiday");
        assertThat(cells.get("2026-10-14").leave().type()).isEqualTo(LeaveType.HALF_TENTATIVE);
    }

    @Test
    void exportRows() {
        List<CalendarService.ExportRow> e = calendar.export(ALL, "2026-10");
        assertThat(e.get(0)).isEqualTo(new CalendarService.ExportRow("Phạm Quốc Dũng", "2026-10-02", "Half day", ""));
        assertThat(e).contains(new CalendarService.ExportRow("JP", "2026-10-12", "Holiday", "Sports Day (JP)"));
    }

    @Test
    void planningWindowDaysAndMemberDayCells() {
        PlanningService.PlanningView v = planning.view(ALL, "2026-10-07", 2);
        assertThat(v.start()).isEqualTo("2026-10-05");
        assertThat(v.end()).isEqualTo("2026-10-18");
        assertThat(v.days()).hasSize(11);
        assertThat(v.days().get(5).extraNote()).isNotNull();
        assertThat(v.days().get(2).isToday()).isTrue();
        Map<String, PlanningService.DayCell> tanaka = new HashMap<>();
        v.rows().stream().filter(r -> r.memberId().equals("tanaka")).findFirst().orElseThrow().days().forEach(c -> tanaka.put(c.date(), c));
        assertThat(tanaka.get("2026-10-12").kind()).isEqualTo("holiday");
        assertThat(tanaka.get("2026-10-14").kind()).isEqualTo("leave_half");
        assertThat(tanaka.get("2026-10-14").tentative()).isTrue();
    }

    @Test
    void planningItemsOverlapTheWindowWithStepColourHints() {
        PlanningService.PlanningView v = planning.view(ALL, "2026-10-05", 2);
        Map<String, PlanningService.PlanBlock> binh = new HashMap<>();
        v.rows().stream().filter(r -> r.memberId().equals("binh")).findFirst().orElseThrow().items()
                .forEach(b -> binh.put(b.card().key(), b));
        assertThat(binh.get("PAY-1213").colorStep()).isEqualTo("fixb");
        assertThat(binh.get("CRM-430").colorStep()).isEqualTo("create");
        assertThat(binh).doesNotContainKey("PAY-1201");
        assertThat(Set.copyOf(v.projects())).isEqualTo(Set.of("PAY", "CRM", "OPS"));
        var cap = v.capacity().stream().filter(c -> c.memberId().equals("chi")).findFirst().orElseThrow().weeks();
        assertThat(cap).hasSize(5);
        assertThat(cap.get(0).level()).isEqualTo("over"); // the demo gives Chi 8h a day, less than the planned work
        assertThat(cap.get(0).pct()).isNotNull();
    }

    @Test
    void planningCapacityScoped() {
        var before = allocations.findByMember("chi");
        jdbc.sql("DELETE FROM allocations WHERE member_id = 'chi'").update();
        allocations.save(new Allocation("chi", "PAY-1100", "2026-09-01", 6), "minh", "2026-10-07T09:00:00Z");
        try {
            var cap = planning.view(new AnkenScope(Set.of("PAY-1100")), "2026-10-05", 1).capacity().stream()
                    .filter(c -> c.memberId().equals("chi")).findFirst().orElseThrow().weeks();
            assertThat(cap.get(0).availableH()).isEqualTo(30); // Mon-Thu plus the extra Saturday 10/10 (leave on Friday) x 6h
        } finally {
            jdbc.sql("DELETE FROM allocations WHERE member_id = 'chi'").update();
            before.forEach(a -> allocations.save(a, "seed", "2026-10-07T09:00:00Z"));
        }
    }

    @Test
    void previewMovesItemsAndFlagsDaysOff() {
        PlanningService.PlanningPreview p = planning.preview(ALL, "2026-10-05", List.of(new PlanChange("PAY-1262", "2026-10-11", "binh")));
        PlanningService.ChangePreview c = p.changes().get(0);
        assertThat(List.of(c.fromStart(), c.fromEnd(), c.fromMember(), c.toStart(), c.toEnd(), c.toMember()))
                .containsExactly("2026-10-09", "2026-10-09", "an", "2026-10-11", "2026-10-11", "binh");
        assertThat(c.dayOff()).containsExactly(new DateReason("2026-10-11", "weekend"));
        Map<String, Double> base = new HashMap<>();
        planning.view(ALL, "2026-10-05", 1).capacity().forEach(x -> base.put(x.memberId(), x.weeks().get(0).plannedH()));
        Map<String, Double> moved = new HashMap<>();
        p.capacity().forEach(x -> moved.put(x.memberId(), x.weeks().get(0).plannedH()));
        assertThat(moved.get("an")).isEqualTo(base.get("an") - 6);
        assertThat(moved.get("binh")).isEqualTo(base.get("binh") + 6);
        assertThatThrownBy(() -> planning.preview(ALL, "2026-10-05", List.of(new PlanChange("PAY-9", "2026-10-11", "binh"))))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void ticketDetailWithWarningsHistoryAndWorklogs() {
        TicketService.TicketDetail t = tickets.detail(ALL, "PAY-1213");
        assertThat(t.card().functionId()).isEqualTo("F03");
        assertThat(t.ownerId()).isEqualTo("binh");
        assertThat(t.warnings()).containsExactly(new TicketService.TicketWarning("late", 1),
                new TicketService.TicketWarning("over", 3.5), new TicketService.TicketWarning("rounds", 3));
        assertThat(t.history().get(t.history().size() - 1)).extracting(h -> h.fromId() + ">" + h.toId()).isEqualTo("minh>binh");
        assertThat(t.worklogs().stream().mapToDouble(w -> w.hours()).sum()).isEqualTo(7.5);
        assertThatThrownBy(() -> tickets.detail(ALL, "PAY-9")).isInstanceOf(NotFound.class);
    }

    @Test
    void qaAnswersFromSyncedData() {
        var a = tickets.ask(ALL, "Which functions are late?");
        // overdue counts every open sub-task past its own planned end, also a later step of a function: PAY-1326 … PAY-1335 of PAY-1300
        assertThat(a.text()).startsWith("18 sub-tasks are past their plan, across 12 functions");
        assertThat(a.chips()).hasSize(18).contains("PAY-1213", "PAY-1219", "PAY-1222", "PAY-1226", "PAY-1235", "PAY-1252")
                .contains("PAY-1326", "PAY-1329", "PAY-1330", "PAY-1333", "PAY-1334", "PAY-1335");
    }

    @Test
    void jiraSettingsMaskTheToken() {
        JiraSettingsService.JiraSettingsView s = jiraSettings.jiraSettings();
        assertThat(s.url()).isEqualTo("https://acme-pay.atlassian.net");
        assertThat(s.tokenMasked()).startsWith("ATATT3xFfGF0").doesNotContain("demo-token");
        assertThat(s.epics()).extracting(JiraSettingsService.EpicView::key).startsWith("PAY-1100", "PAY-1300");
        assertThat(s.epics().get(0).title()).isEqualTo("Payment flow revision");
        assertThat(s.lastFullSync()).isNotNull();
    }

    @Test
    void connectionTestUsesUnsavedValuesAndTheStoredToken(@Autowired SettingsStore store, @Autowired AnkenRepository ankenRepo,
                                                         @Autowired IssueRepository issues, @Autowired SyncRunRepository runs,
                                                         @Autowired FakeJira jira, @Autowired SecretBox secrets,
                                                         @Autowired SyncService sync) {
        JiraReaderFactory checksToken = (url, email, token) -> token.startsWith("ATATT") ? jira : new Rejecting();
        JiraSettingsService s = new JiraSettingsService(store, ankenRepo, issues, runs, jira, checksToken, secrets, sync);
        assertThat(s.testJira("https://acme-pay.atlassian.net", "x@acme.vn", null)).isEqualTo("ok");
        assertThat(s.testJira("https://elsewhere.example.com", "x@acme.vn", "short")).isEqualTo("fail");
    }

    /** A site that rejects the credentials. */
    static final class Rejecting implements JiraReader {
        @Override
        public List<IssueSnapshot> search(List<String> epicKeys, Instant updatedSince) {
            return List.of();
        }

        @Override
        public Optional<EpicSnapshot> findEpic(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<IssueSnapshot> findIssue(String key) {
            return Optional.empty();
        }

        @Override
        public List<JiraField> listFields() {
            return List.of();
        }

        @Override
        public boolean testConnection() {
            return false;
        }
    }

    @Test
    void fieldMappingCheck() {
        JiraSettingsService.FieldMappingView v = jiraSettings.fieldMapping();
        assertThat(v.fields()).filteredOn(JiraSettingsService.AppField::mappable)
                .extracting(f -> f.key() + "=" + f.fieldId())
                .containsExactly("planned_start=customfield_10020", "planned_end=customfield_10016", "effort=timeoriginalestimate",
                        "remaining=timeestimate");
        assertThat(v.fields()).filteredOn(f -> !f.mappable()).extracting(JiraSettingsService.AppField::fieldId)
                .contains("summary", "status", "assignee", "parent", "worklog");
        assertThat(v.catalog()).extracting(JiraField::name).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        Map<String, JiraSettingsService.FieldCheck> r = new HashMap<>();
        jiraSettings.checkFieldMapping("PAY-1203", Map.of("planned_start", "customfield_10020", "planned_end", "summary", "effort", ""))
                .results().forEach(x -> r.put(x.target(), x));
        assertThat(r.get("planned_start").status()).isEqualTo("ok");
        assertThat(r.get("planned_start").value()).isEqualTo("2026-09-17");
        assertThat(r.get("planned_end").status()).isEqualTo("type_mismatch");
        assertThat(r.get("effort").status()).isEqualTo("not_mapped");
        assertThat(r.get("remaining").status()).isEqualTo("ok");
        assertThatThrownBy(() -> jiraSettings.checkFieldMapping("PAY-9", Map.of())).isInstanceOf(NotFound.class);
    }

    @Test
    void statusMappingListsSeenStatuses() {
        Map<String, JiraSettingsService.StatusRow> rows = new HashMap<>();
        jiraSettings.statusMapping().forEach(r -> rows.put(r.name(), r));
        assertThat(rows.get("Resolved").mapped()).isEqualTo("R");
        assertThat(rows.get("In Progress").effective()).isEqualTo("W");
        assertThat(rows.get("Closed").effective()).isEqualTo("C");
    }

    @Test
    void structureListsJiraTasksWithTheirTemplate() {
        JiraSettingsService.StructureView v = jiraSettings.structure(null);
        assertThat(v.phases()).containsExactly("Prepare", "Implement_Coding", "Function Unit Test");
        assertThat(v.tasks()).isEmpty();
        assertThat(v.seen().stream().filter(t -> t.epicKey().equals("PAY-1100")))
                .extracting(t -> t.key() + "/" + t.phase() + "/" + t.template())
                .containsExactly("PAY-1101/Prepare/null", "PAY-1102/Implement_Coding/null", "PAY-1103/Function Unit Test/null");
        WorkStructure draft = new WorkStructure(List.of("Coding"), List.of(new TaskTemplate("[Implement_Coding] *", "Coding", "UCI")));
        JiraSettingsService.SeenTask t = jiraSettings.previewStructure(null, draft).seen().stream()
                .filter(x -> x.key().equals("PAY-1102")).findFirst().orElseThrow();
        assertThat(List.of(t.phase(), t.template())).containsExactly("Coding", 0);
        assertThat(jiraSettings.structure(null).tasks()).isEmpty(); // a preview does not save
    }

    @Test
    void saveStructureRejectsInvalidSettings() {
        List<WorkStructure> bad = List.of(
                new WorkStructure(List.of("A", "A"), List.of()),
                new WorkStructure(List.of(" "), List.of()),
                new WorkStructure(List.of("A"), List.of(new TaskTemplate("X", "B", ""))),
                new WorkStructure(List.of("A"), List.of(new TaskTemplate("X", "A", ""), new TaskTemplate(" x ", "A", ""))),
                new WorkStructure(List.of("A"), List.of(new TaskTemplate(" ", "A", ""))));
        for (WorkStructure ws : bad) {
            assertThatThrownBy(() -> jiraSettings.saveStructure(null, ws)).isInstanceOf(Invalid.class);
        }
        assertThat(jiraSettings.structure(null).phases()).containsExactly("Prepare", "Implement_Coding", "Function Unit Test");
    }

    @Test
    void nameRulePreviewAndValidation() {
        JiraSettingsService.NameRulePreview p = jiraSettings.previewNameRule(null, tracker.domain.Naming.DEFAULT_RULE, null,
                "[Implement_Coding][F03] Domestic card payment – Fix comment BAL");
        assertThat(p.error()).isNull();
        assertThat(p.step()).isEqualTo(StepKind.FIX_BAL.value());
        assertThat(p.values().get("function_id")).isEqualTo("F03");
        assertThat(p.matched()).isGreaterThanOrEqualTo(60).isLessThan(p.total());
        assertThat(jiraSettings.previewNameRule(null, "[{phase}] {step}", null, "x").error()).isEqualTo("Unknown placeholder {phase}");
        assertThat(jiraSettings.previewNameRule(null, "[{prefix}] {step}", null, "[X] Create").error())
                .isEqualTo("Pattern must contain {function_id} or {function_name}");
        assertThatThrownBy(() -> jiraSettings.saveNameRules(null, "[{prefix}] {step}", Map.of("create", "Create")))
                .isInstanceOf(Invalid.class);
    }

    @Test
    void myWorkOverdueMatchesFlags(@Autowired TrackerContextLoader context) {
        TrackerContext ctx = context.load(ALL);
        for (String member : ctx.people) {
            WorkService.MyWork w = work.myWork(ALL, member, "week", null);
            assertThat(w.delay()).as(member).allMatch(TaskCard::late);
            List<String> overdue = w.backlog().stream().filter(g -> g.kind().equals("overdue"))
                    .flatMap(g -> g.items().stream()).map(TaskCard::key).toList();
            for (WorkService.BacklogGroup g : w.backlog()) {
                if (g.kind().equals("overdue")) {
                    assertThat(g.items()).as(member).allMatch(TaskCard::late);
                } else {
                    assertThat(g.items()).as(member + " " + g.kind()).noneMatch(TaskCard::late);
                }
            }
            List<String> shown = new ArrayList<>(overdue);
            w.delay().forEach(c -> shown.add(c.key()));
            List<String> lateOpen = ctx.subtasks().stream()
                    .filter(i -> member.equals(i.assigneeId()) && i.status() != tracker.domain.Status.CLOSED)
                    .filter(i -> ctx.flags.get(i.key()).late()).map(Issue::key).toList();
            assertThat(shown).as(member).containsAll(lateOpen);
        }
    }

    @Test
    void metaMembersAndMyWork() {
        SystemService.Meta m = system.meta(ALL);
        assertThat(List.of(m.mode(), m.today(), m.capabilities().jiraWrite(), m.sync().status())).containsExactly("demo", "2026-10-07", true, "ok");
        assertThat(m.projects()).containsExactly("CRM", "OPS", "PAY");
        assertThat(system.members(ALL)).extracting(x -> x.id()).startsWith("an", "binh");

        WorkService.MyWork w = work.myWork(ALL, "binh", "week", null);
        assertThat(w.nextDay()).isEqualTo("2026-10-08");
        assertThat(w.delay()).extracting(TaskCard::key).containsExactly("PAY-1012", "PAY-1186", "PAY-1184", "PAY-1185", "PAY-1213");
        assertThat(w.dueToday()).extracting(TaskCard::key).containsExactlyInAnyOrder("PAY-1183", "PAY-1187", "PAY-1188", "PAY-1265");
        assertThat(w.dueNext()).extracting(TaskCard::key).contains("PAY-1220");
        assertThat(w.backlog().get(0).kind()).isEqualTo("overdue");
        var weeks = w.backlog().stream().filter(g -> g.kind().equals("week")).toList();
        assertThat(weeks.get(0).start()).isEqualTo("2026-10-05");
        assertThat(weeks.get(0).current()).isTrue();
        assertThat(w.openCount()).isEqualTo(w.backlog().stream().mapToInt(g -> g.items().size()).sum());

        WorkService.MyWork month = work.myWork(ALL, "binh", "month", null);
        assertThat(month.backlog()).extracting(WorkService.BacklogGroup::kind).startsWith("overdue", "month");
        assertThat(month.backlog().get(1).start()).isEqualTo("2026-10");
        WorkService.MyWork minh = work.myWork(ALL, "minh", "week", null);
        assertThat(minh.relatedScope()).isEqualTo("reviewer");
        assertThat(minh.related()).allMatch(c -> !"balr".equals(c.step()));
        assertThatThrownBy(() -> work.myWork(ALL, "nobody", "week", null)).isInstanceOf(NotFound.class);
    }
}
