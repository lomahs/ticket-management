package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.PlanCellRepository;
import tracker.application.ports.PlanCellRepository.Cell;
import tracker.application.ports.PlanCellRepository.JiraEdit;
import tracker.application.ports.PlanDayRepository;
import tracker.application.service.DetailPlanService;
import tracker.application.service.DetailPlanService.CellIn;
import tracker.application.service.DetailPlanService.DayHours;
import tracker.application.service.DetailPlanService.JiraLineIn;
import tracker.application.service.DetailPlanService.DetailPlanView;
import tracker.application.service.DetailPlanService.DraftIn;
import tracker.application.service.DetailPlanService.MatrixLine;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.DetailPlanService.StepIn;
import tracker.application.service.EstimationService;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.TicketService;
import tracker.application.service.TicketService.NewTicket;
import tracker.domain.AccessStatus;
import tracker.domain.Allocation;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Viewer;
import tracker.domain.Worklog;

/** The detail plan as a function–step × day matrix: lines of the unpushed rows and of the sub-tasks in Jira. Demo "today" is 2026-10-07. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DetailPlanMatrixTest {
    static final AnkenScope ALL = new AnkenScope(Set.copyOf(Dataset.TRACKED_EPICS));
    static final String EPIC = "PAY-1100";
    static final Viewer ADMIN = new Viewer("minh.hoang@acme.vn", "Minh", "minh", Role.BAL, true, AccessStatus.ACTIVE,
            Permissions.defaults(Role.BAL));

    @Autowired
    EstimationService estimation;
    @Autowired
    EstimationRepository estimationRows;
    @Autowired
    MasterScheduleService schedule;
    @Autowired
    DetailPlanService detail;
    @Autowired
    TicketService tickets;
    @Autowired
    PlanCellRepository cells;
    @Autowired
    PlanDayRepository planDays;
    @Autowired
    IssueRepository issues;
    @Autowired
    TrackerContextLoader loader;
    @Autowired
    AllocationRepository allocationRows;

    Map<String, String> tasks = new HashMap<>();

    String task(String name) {
        return tasks.computeIfAbsent(name, n -> tickets.applyBatch(ADMIN, ALL, EPIC, List.of(),
                List.of(new NewTicket("t", false, null, null, "[PAY] " + n, "an", null, null, null, null, List.of()))).created().get("t"));
    }

    /** Login (5 steps, 8h) and Run (a ticket, 16h) under ITA Tasks, Pay (3 steps) under ITB; ITA from Monday 12, ITB from Monday 19. */
    List<EstimationRow> scheduled() {
        return scheduled("2026-10-12");
    }

    List<EstimationRow> scheduled(String itaStart) {
        task("ITB Execute");
        estimation.save(ALL, EPIC, List.of(row(0, "Login", 5, 8, task("ITA Create test case")), row(1, "Run", null, 16, task("ITA Execute")),
                row(2, "Pay", 3, 8, task("ITB Create test case"))));
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", itaStart, List.of("an", "binh", "minh")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("an", "minh"))), "Kick-off");
        return estimationRows.findByEpic(EPIC);
    }

    static EstimationRow row(int pos, String fn, Integer steps, double effort, String task) {
        return new EstimationRow(0, EPIC, pos, "", "", fn, steps, effort, null, null, null, task, null);
    }

    void assign(List<EstimationRow> rows, RowAssign... assigns) {
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(), List.of(assigns)));
    }

    /** Login an/minh/tanaka, Run an, Pay an/minh: every slot Auto allocate needs, picked. */
    List<EstimationRow> staffed() {
        List<EstimationRow> rows = scheduled();
        assign(rows, new RowAssign(rows.get(0).id(), "an", "minh", "tanaka"), new RowAssign(rows.get(1).id(), "an", null, null),
                new RowAssign(rows.get(2).id(), "an", "minh", null));
        return rows;
    }

    /**
     * {@link #staffed()}, and 8h a day on the anken for Chi: the demo's PAY-1176 is Chi's and not fully planned, but Chi gives
     * the anken no hours, so Auto allocate could not place it.
     */
    List<EstimationRow> fillable() {
        allocationRows.save(new Allocation("chi", EPIC, "2026-09-01", 8), "test", "2026-10-07T00:00:00Z");
        return staffed();
    }

    static MatrixLine line(DetailPlanView v, String ref) {
        return v.lines().stream().filter(l -> l.ref().equals(ref)).findFirst().orElseThrow();
    }

    /** Hours per "member date" of one sub-task in the Effort per day items. */
    static Map<String, Double> hoursOf(DetailPlanView v, String title) {
        Map<String, Double> out = new TreeMap<>();
        v.days().forEach(d -> d.items().stream().filter(i -> !i.other() && i.title().equals(title))
                .forEach(i -> out.merge(d.memberId() + " " + d.date(), i.hours(), Double::sum)));
        return out;
    }

    /** A 16h sub-task in Jira under ITA Execute, planned 2026-10-06..08 (Tue–Thu, around today). */
    String jiraSubtask() {
        return tickets.applyBatch(ADMIN, ALL, EPIC, List.of(), List.of(new NewTicket("s", true, task("ITA Execute"), null, "[PAY] Export", "an",
                null, "2026-10-06", "2026-10-08", 16.0, List.of()))).created().get("s");
    }

    /** A step of the function F99 ("Probe") in Jira under ITA Execute, assigned to An. */
    Issue probe(String key, StepKind step, Status status, String start, String end, double estimate) {
        return new Issue(key, "PAY", EPIC, task("ITA Execute"), IssueKind.SUBTASK, "[PAY] " + key, "", status, "an", start, end, estimate,
                status == Status.CLOSED ? 0 : estimate, "2026-10-01", status.value(), "F99", "Probe", step, List.of(), List.of(), "an", List.of());
    }

    @Test
    void newLinesShowTheirCells() {
        List<EstimationRow> rows = scheduled();
        long run = rows.get(1).id();
        assign(rows, new RowAssign(run, "an", null, null));
        cells.saveRowCells(List.of(new Cell(String.valueOf(run), "2026-10-12", 8, false), new Cell(String.valueOf(run), "2026-10-13", 6, false)));
        DetailPlanView v = detail.view(ALL, EPIC);
        MatrixLine l = line(v, String.valueOf(run));
        assertThat(l.rowId()).isEqualTo(run);
        assertThat(l.issueKey()).isNull();
        assertThat(l.picId()).isEqualTo("an");
        assertThat(l.summary()).isEqualTo("[PAY] Run");
        assertThat(l).extracting(MatrixLine::totalH, MatrixLine::plannedH, MatrixLine::notYetH, MatrixLine::start, MatrixLine::end)
                .containsExactly(16.0, 14.0, 2.0, "2026-10-12", "2026-10-13");
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-12", 8, false), new DayHours("2026-10-13", 6, false));
        assertThat(v.pushBlockers()).contains("[PAY] Run: 2h not planned yet");
        assertThat(hoursOf(v, "[PAY] Run")).containsExactly(Map.entry("an 2026-10-12", 8.0), Map.entry("an 2026-10-13", 6.0));
        // the unpushed lines come first, in row order, a line per step
        assertThat(v.lines().subList(0, 9)).extracting(MatrixLine::rowId).allMatch(Objects::nonNull);
        assertThat(v.lines().subList(9, v.lines().size())).allMatch(x -> x.rowId() == null && x.issueKey() != null);
    }

    @Test
    void fixCommentHoursAreShared() {
        List<EstimationRow> rows = scheduled();
        long login = rows.get(0).id();
        assign(rows, new RowAssign(login, "an", "minh", null));
        cells.saveRowCells(List.of(new Cell(login + ":fixb", "2026-10-13", 1, false)));
        DetailPlanView v = detail.view(ALL, EPIC);
        Map<String, Double> h = hoursOf(v, line(v, login + ":fixb").summary());
        assertThat(h).containsOnlyKeys("an 2026-10-13", "minh 2026-10-13");
        assertThat(h.get("an 2026-10-13")).isCloseTo(0.7, within(1e-9));
        assertThat(h.get("minh 2026-10-13")).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void jiraLineShowsPastPlanAndPendingFuture() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 8.0, "2026-10-07", 8.0));
        cells.saveJiraEdit(EPIC, new JiraEdit(key, 10.0, null));
        cells.saveJiraCells(EPIC, List.of(new Cell(key, "2026-10-06", 5, false), new Cell(key, "2026-10-08", 4, false),
                new Cell(key, "2026-10-09", 6, false)));
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        assertThat(l.issueKey()).isEqualTo(key);
        assertThat(l.rowId()).isNull();
        // before today Jira's plan (the pending 5h is ignored); from today on the pending cells
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-06", 8, false), new DayHours("2026-10-08", 4, false),
                new DayHours("2026-10-09", 6, false));
        assertThat(l.totalH()).isEqualTo(l.spentH() + 10);
        assertThat(l.notYetH()).isZero();
        assertThat(l.pending()).isTrue();
        assertThat(l.closed()).isFalse();
    }

    @Test
    void pushedDaysShowEvenWhenTheyNoLongerAddUp() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-07", 8.0, "2026-10-08", 2.0)); // 10h of a 16h sub-task
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-07", 8, false), new DayHours("2026-10-08", 2, false));
        assertThat(l.pending()).isFalse();
    }

    @Test
    void removedStepCountsForNothing() {
        List<EstimationRow> rows = scheduled();
        long login = rows.get(0).id();
        assign(rows, new RowAssign(login, "an", "minh", "tanaka"));
        cells.saveRowCells(List.of(new Cell(login + ":balr", "2026-10-13", 2, false)));
        String summary = line(detail.view(ALL, EPIC), login + ":balr").summary();
        assertThat(hoursOf(detail.view(ALL, EPIC), summary)).isNotEmpty();
        DetailPlanView v = detail.editStep(ALL, EPIC, new StepIn(login, "balr", null, true));
        assertThat(line(v, login + ":balr").removed()).isTrue();
        assertThat(hoursOf(v, summary)).isEmpty();
        assertThat(v.pushBlockers()).noneMatch(b -> b.startsWith(summary + ":"));
    }

    @Test
    void outOfOrderBlocksPush() {
        List<EstimationRow> rows = scheduled();
        long login = rows.get(0).id();
        assign(rows, new RowAssign(login, "an", "minh", "tanaka"));
        cells.saveRowCells(List.of(new Cell(login + ":create", "2026-10-13", 8, false), new Cell(login + ":balr", "2026-10-12", 2, false)));
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(line(v, login + ":balr").outOfOrder()).isTrue();
        assertThat(line(v, login + ":create").outOfOrder()).isFalse();
        assertThat(v.pushBlockers()).contains("[PAY] Login – BAL Review: starts before the step before it ends");
    }

    @Test
    void closedJiraLineIsReadOnlyAndNeverBlocks() {
        scheduled();
        issues.save(new Issue("PAY-9002", "PAY", EPIC, task("ITA Execute"), IssueKind.SUBTASK, "[PAY] PAY-9002", "", Status.CLOSED, "an",
                "2026-10-12", "2026-10-16", 40, 0, "2026-10-07", Status.CLOSED.value(), null, null, null, List.of(), List.of(), "an", List.of()));
        DetailPlanView v = detail.view(ALL, EPIC);
        MatrixLine l = line(v, "PAY-9002");
        assertThat(l.closed()).isTrue();
        assertThat(l.totalH()).isEqualTo(l.spentH());
        assertThat(v.pushBlockers()).noneMatch(b -> b.startsWith("[PAY] PAY-9002"));
        // a Closed step that (by its plan) ends after the next open step starts does not put that step out of order
        issues.save(probe("PAY-9111", StepKind.CREATE, Status.CLOSED, "2026-10-14", "2026-10-15", 8));
        issues.save(probe("PAY-9112", StepKind.BAL_REVIEW, Status.WIP, "2026-10-12", "2026-10-13", 2));
        v = detail.view(ALL, EPIC);
        assertThat(line(v, "PAY-9111").end()).isGreaterThan(line(v, "PAY-9112").start());
        assertThat(line(v, "PAY-9112").outOfOrder()).isFalse();
        assertThat(v.pushBlockers()).noneMatch(b -> b.endsWith("starts before the step before it ends") && b.startsWith("[PAY] PAY-91"));
    }

    @Test
    void aJiraStepIsInOrderByItsDaysFromTodayOn() {
        scheduled();
        issues.save(probe("PAY-9121", StepKind.CREATE, Status.WIP, "2026-10-12", "2026-10-13", 16));
        issues.save(probe("PAY-9122", StepKind.BAL_REVIEW, Status.WIP, "2026-10-05", "2026-10-06", 2)); // only days before today
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(line(v, "PAY-9122").start()).isLessThan(line(v, "PAY-9121").end());
        assertThat(line(v, "PAY-9122").outOfOrder()).isFalse(); // its days are locked: nothing to fix
        assertThat(v.pushBlockers()).noneMatch(b -> b.startsWith("[PAY] PAY-9122: starts before"));
    }

    @Test
    void aPendingPicAloneKeepsJirasPlan() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 8.0, "2026-10-07", 8.0));
        cells.saveJiraEdit(EPIC, new JiraEdit(key, null, "binh"));
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-06", 8, false), new DayHours("2026-10-07", 8, false));
        assertThat(l.pending()).isTrue();
        assertThat(l.picId()).isEqualTo("binh");
    }

    @Test
    void aPendingPicTakesTheHoursFromTodayOn() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 8.0, "2026-10-07", 8.0));
        cells.saveJiraEdit(EPIC, new JiraEdit(key, null, "binh"));
        // the day before today stays An's; today on is Bình's
        assertThat(hoursOf(detail.view(ALL, EPIC), "[PAY] Export")).containsExactly(Map.entry("an 2026-10-06", 8.0),
                Map.entry("binh 2026-10-07", 8.0));
    }

    @Test
    void onlyDaysFromTodayOnAreOverTheAllocation() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 16.0)); // a locked day, far over An's allocation
        cells.saveJiraCells(EPIC, List.of(new Cell(key, "2026-10-08", 16, false)));
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(hoursOf(v, "[PAY] Export")).containsEntry("an 2026-10-06", 16.0).containsEntry("an 2026-10-08", 16.0);
        assertThat(v.overDay()).extracting(o -> o.memberId() + " " + o.date()).contains("an 2026-10-08");
        assertThat(v.overDay()).allMatch(o -> o.date().compareTo("2026-10-07") >= 0);
    }

    @Test
    void windowRunsOverMasterAndCells() {
        List<EstimationRow> rows = scheduled("2026-08-03"); // ITA from Monday 3 August, before every open sub-task of the demo
        long login = rows.get(0).id();
        long run = rows.get(1).id();
        assign(rows, new RowAssign(run, "an", null, null));
        cells.saveRowCells(List.of(new Cell(String.valueOf(run), "2027-01-04", 2, false), // a Monday after every master End
                new Cell(login + ":balr", "2026-07-06", 2, false)));
        detail.editStep(ALL, EPIC, new StepIn(login, "balr", null, true)); // a removed step's cells do not count
        issues.save(probe("PAY-9131", StepKind.CREATE, Status.CLOSED, "2026-07-06", "2026-07-10", 8)); // nor do a Closed one's
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(line(v, "PAY-9131").cells()).isNotEmpty();
        assertThat(v.window().getFirst()).isEqualTo("2026-08-03");
        assertThat(v.window().getLast()).isEqualTo("2027-01-04");
        assertThat(v.window()).contains("2026-10-12", "2026-10-13").doesNotContain("2026-07-06", "2026-10-17", "2026-10-18");
        // working days only: weekends aside, unless the calendar makes one a working day (the demo's Saturday 2026-10-10)
        TrackerContext ctx = loader.load(ALL);
        assertThat(v.window()).allMatch(ctx.cal::visible);
        assertThat(v.window()).filteredOn(d -> {
            DayOfWeek w = LocalDate.parse(d).getDayOfWeek();
            return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
        }).allMatch(d -> !d.equals("2026-10-17") && !d.equals("2026-10-18"));
        assertThat(v.window()).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void typedCellsAreSavedAndZeroClears() {
        List<EstimationRow> rows = scheduled();
        long run = rows.get(1).id();
        assign(rows, new RowAssign(run, "an", null, null));
        String ref = String.valueOf(run);
        DetailPlanView v = detail.saveCells(ALL, EPIC, List.of(new CellIn(ref, "2026-10-12", 8), new CellIn(ref, "2026-10-13", 8)));
        assertThat(line(v, ref).plannedH()).isEqualTo(16);
        assertThat(line(v, ref).notYetH()).isZero();
        v = detail.saveCells(ALL, EPIC, List.of(new CellIn(ref, "2026-10-13", 0)));
        assertThat(line(v, ref).cells()).containsExactly(new DayHours("2026-10-12", 8, false));
        assertThat(line(v, ref).end()).isEqualTo("2026-10-12");
    }

    @Test
    void aCellOutsideThePlanIsRefused() {
        scheduled();
        String key = jiraSubtask();
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn("999:create", "2026-10-12", 1))))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "ref");
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-06", 1))))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "day");
    }

    @Test
    void theFirstTypedCellOfAJiraLineKeepsJirasPlan() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-07", 8.0, "2026-10-08", 4.0));
        DetailPlanView v = detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-09", 2)));
        assertThat(line(v, key).cells()).containsExactly(new DayHours("2026-10-07", 8, false), new DayHours("2026-10-08", 4, false),
                new DayHours("2026-10-09", 2, false));
        assertThat(line(v, key).pending()).isTrue();
    }

    @Test
    void totalOfAJiraLineChangesOnlyRemaining() {
        scheduled();
        String key = tickets.applyBatch(ADMIN, ALL, EPIC, List.of(), List.of(new NewTicket("s", true, task("ITA Execute"), null, "[PAY] Export",
                "an", null, "2026-10-06", "2026-10-08", 3.0, List.of()))).created().get("s");
        Issue i = issues.findByKey(key).orElseThrow();
        issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), i.status(), i.assigneeId(),
                i.plannedStart(), i.plannedEnd(), 3, 2, i.created(), i.jiraStatus(), i.functionId(), i.functionName(), i.step(),
                List.of(new Worklog("2026-10-06", "an", 1, "")), i.events(), i.reporterId(), i.labels()));
        DetailPlanView v = detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(5.0, null));
        assertThat(line(v, key).totalH()).isEqualTo(5);
        assertThat(cells.jiraEdits(EPIC)).containsExactly(new JiraEdit(key, 4.0, null));
        assertThat(issues.findByKey(key).orElseThrow().estimateH()).isEqualTo(3);
        assertThatThrownBy(() -> detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(0.5, null)))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "total_h");
    }

    @Test
    void picOfAJiraLineMovesItsShare() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 8.0, "2026-10-07", 8.0));
        DetailPlanView v = detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(null, "binh"));
        assertThat(line(v, key).picId()).isEqualTo("binh");
        assertThat(line(v, key).pending()).isTrue();
        assertThat(hoursOf(v, "[PAY] Export")).containsExactly(Map.entry("an 2026-10-06", 8.0), Map.entry("binh 2026-10-07", 8.0));
        assertThatThrownBy(() -> detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(null, "nhan")))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "pic_id");
    }

    @Test
    void zeroedJiraCellsStayClearedAndDoNotReseed() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-07", 8.0, "2026-10-08", 4.0));
        DetailPlanView v = detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-07", 0), new CellIn(key, "2026-10-08", 0)));
        assertThat(line(v, key).cells()).isEmpty();
        assertThat(line(v, key).notYetH()).isEqualTo(line(v, key).totalH() - line(v, key).spentH());
        v = detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-09", 3)));
        assertThat(line(v, key).cells()).containsExactly(new DayHours("2026-10-09", 3, false));
    }

    @Test
    void anEditEqualToJirasValuesIsNoEdit() {
        scheduled();
        String key = jiraSubtask();
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        DetailPlanView v = detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(l.totalH(), "an"));
        assertThat(cells.jiraEdits(EPIC)).isEmpty();
        assertThat(line(v, key).pending()).isFalse();
    }

    @Test
    void autoAllocateKeepsTypedCellsAndFillsTheRest() {
        List<EstimationRow> rows = fillable();
        String run = String.valueOf(rows.get(1).id());
        detail.saveCells(ALL, EPIC, List.of(new CellIn(run, "2026-10-12", 4)));
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        MatrixLine l = line(v, run);
        assertThat(l.cells()).filteredOn(c -> c.day().equals("2026-10-12")).containsExactly(new DayHours("2026-10-12", 4, false));
        assertThat(l.notYetH()).isZero();
        assertThat(l.cells()).filteredOn(c -> !c.day().equals("2026-10-12")).isNotEmpty()
                .allMatch(DayHours::auto).allMatch(c -> c.day().compareTo("2026-10-12") >= 0);
    }

    @Test
    void autoAllocateKeepsStepOrder() {
        List<EstimationRow> rows = fillable();
        long login = rows.get(0).id();
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        List<MatrixLine> lines = v.lines().stream().filter(l -> Objects.equals(l.rowId(), login)).toList();
        assertThat(lines).hasSize(5).allMatch(l -> !l.outOfOrder()).allMatch(l -> Math.abs(l.notYetH()) < 0.01)
                .allMatch(l -> l.cells().stream().allMatch(DayHours::auto));
        assertThat(line(v, login + ":create").end()).isLessThanOrEqualTo(line(v, login + ":balr").start());
        assertThat(line(v, login + ":create").start()).isGreaterThanOrEqualTo("2026-10-12"); // the phase's master Start
    }

    @Test
    void autoAllocateFillsJiraNotYet() {
        fillable();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 4.0));
        cells.saveJiraCells(EPIC, List.of(new Cell(key, "2026-10-07", 4, false), new Cell(key, "2026-10-08", 4, false)));
        MatrixLine before = line(detail.view(ALL, EPIC), key);
        assertThat(before.pending()).isTrue();
        assertThat(before.notYetH()).isEqualTo(before.totalH() - before.spentH() - 8);
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        MatrixLine l = line(v, key);
        assertThat(l.notYetH()).isZero();
        assertThat(l.cells()).filteredOn(DayHours::auto).isNotEmpty().allMatch(c -> c.day().compareTo("2026-10-08") >= 0);
        // the typed cells stay typed, with Auto allocate's hours added on their day
        assertThat(l.cells()).filteredOn(c -> c.day().equals("2026-10-07")).containsExactly(new DayHours("2026-10-07", 4, false));
        assertThat(l.cells()).filteredOn(c -> c.day().equals("2026-10-08")).singleElement()
                .satisfies(c -> assertThat(c.auto()).isFalse()).satisfies(c -> assertThat(c.hours()).isGreaterThanOrEqualTo(4));
    }

    @Test
    void autoAllocateKeepsJirasPlanOfALineItFills() {
        fillable();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 4.0, "2026-10-07", 4.0)); // 8h of a 16h sub-task planned, none pending
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        MatrixLine l = line(v, key);
        assertThat(l.pending()).isTrue();
        assertThat(l.notYetH()).isZero();
        assertThat(l.cells()).filteredOn(c -> c.day().equals("2026-10-06")).containsExactly(new DayHours("2026-10-06", 4, false));
        assertThat(l.cells()).filteredOn(c -> c.day().equals("2026-10-07")).singleElement()
                .satisfies(c -> assertThat(c.auto()).isFalse()).satisfies(c -> assertThat(c.hours()).isGreaterThanOrEqualTo(4));
        assertThat(cells.jiraCells(EPIC).get(key)).anyMatch(c -> c.day().equals("2026-10-07") && !c.auto());
    }

    @Test
    void autoAllocateRefusesALinePeopleHaveNoHoursFor() {
        List<EstimationRow> rows = staffed();
        long run = rows.get(1).id();
        MatrixLine chi = line(detail.view(ALL, EPIC), "PAY-1176");
        assertThat(chi.picId()).isEqualTo("chi");
        assertThat(chi.notYetH()).isPositive();
        assertThatThrownBy(() -> detail.autoAllocate(ALL, EPIC)).isInstanceOf(AppError.Invalid.class)
                .hasMessage("chi has no free hours on PAY-1100 from 2026-10-08").hasFieldOrPropertyWithValue("field", "plan");
        // the people stay; no cell is written
        assertThat(estimationRows.findByEpic(EPIC)).filteredOn(r -> r.id() == run).singleElement()
                .satisfies(r -> assertThat(r.baId()).isEqualTo("an"));
        assertThat(cells.rowCells(EPIC)).isEmpty();
        assertThat(cells.jiraCells(EPIC)).isEmpty();
    }

    @Test
    void autoAllocateNeedsTheMasterSchedule() {
        task("ITB Execute");
        estimation.save(ALL, EPIC, List.of(row(0, "Run", null, 16, task("ITA Execute"))));
        assertThatThrownBy(() -> detail.autoAllocate(ALL, EPIC)).isInstanceOf(AppError.Invalid.class)
                .hasMessage("Save a master schedule first");
    }

    @Test
    void clearKeepInputDropsOnlyAutoCells() {
        List<EstimationRow> rows = scheduled();
        String run = String.valueOf(rows.get(1).id());
        String key = jiraSubtask();
        cells.saveRowCells(List.of(new Cell(run, "2026-10-12", 4, false), new Cell(run, "2026-10-13", 8, true)));
        cells.saveJiraCells(EPIC, List.of(new Cell(key, "2026-10-08", 3, false), new Cell(key, "2026-10-09", 5, true)));
        cells.saveJiraEdit(EPIC, new JiraEdit(key, 10.0, null));
        DetailPlanView v = detail.clear(ALL, EPIC, true);
        assertThat(line(v, run).cells()).containsExactly(new DayHours("2026-10-12", 4, false));
        assertThat(cells.jiraCells(EPIC).get(key)).containsExactly(new Cell(key, "2026-10-08", 3, false));
        assertThat(cells.jiraEdits(EPIC)).containsExactly(new JiraEdit(key, 10.0, null));
        assertThat(line(v, key).pending()).isTrue();
    }

    @Test
    void clearAllDropsCellsAndPendingJira() {
        List<EstimationRow> rows = scheduled();
        String run = String.valueOf(rows.get(1).id());
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-07", 8.0, "2026-10-08", 8.0));
        cells.saveRowCells(List.of(new Cell(run, "2026-10-12", 4, false), new Cell(run, "2026-10-13", 8, true)));
        cells.saveJiraCells(EPIC, List.of(new Cell(key, "2026-10-08", 3, false), new Cell(key, "2026-10-09", 5, true)));
        cells.saveJiraEdit(EPIC, new JiraEdit(key, 10.0, "binh"));
        DetailPlanView v = detail.clear(ALL, EPIC, false);
        assertThat(cells.rowCells(EPIC)).isEmpty();
        assertThat(line(v, run).cells()).isEmpty();
        assertThat(cells.jiraCells(EPIC)).isEmpty();
        assertThat(cells.jiraEdits(EPIC)).isEmpty();
        MatrixLine l = line(v, key);
        assertThat(l.pending()).isFalse();
        assertThat(l.picId()).isEqualTo("an");
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-07", 8, false), new DayHours("2026-10-08", 8, false));
    }
}
