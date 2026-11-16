package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraRejected;
import tracker.application.ports.JiraWriteLog;
import tracker.application.ports.JiraWriter;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.ports.OnboardingRepository;
import tracker.application.ports.PlanVersionRepository;
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
import tracker.application.service.DetailPlanService.PushIn;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.DetailPlanService.ShiftIn;
import tracker.application.service.DetailPlanService.StepIn;
import tracker.application.service.AllocationService;
import tracker.application.service.Baselines;
import tracker.application.service.EstimationService;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.TicketService;
import tracker.application.service.TicketService.NewTicket;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScope;
import tracker.domain.Baseline;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.Leave;
import tracker.domain.LeaveType;
import tracker.domain.IssueKind;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Steps;
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
    CalendarRepository calendar;

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
        // a past day typed here replaces Jira's (5h on the 6th); from today on the pending cells alone (the 7th goes)
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-06", 5, false), new DayHours("2026-10-08", 4, false),
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
    void aResolvedJiraLineIsDoneAndCannotChange() {
        scheduled();
        issues.save(new Issue("PAY-9003", "PAY", EPIC, task("ITA Execute"), IssueKind.SUBTASK, "[PAY] PAY-9003", "", Status.RESOLVED, "an",
                "2026-10-12", "2026-10-16", 40, 4, "2026-10-07", Status.RESOLVED.value(), null, null, null, List.of(), List.of(), "an", List.of()));
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(line(v, "PAY-9003").closed()).isTrue();
        assertThat(v.pushBlockers()).noneMatch(b -> b.startsWith("[PAY] PAY-9003"));
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn("PAY-9003", "2026-10-12", 2))))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "ref");
        assertThatThrownBy(() -> detail.editJiraLine(ALL, EPIC, "PAY-9003", new JiraLineIn(null, "binh")))
                .isInstanceOf(AppError.Invalid.class);
        assertThatThrownBy(() -> detail.shift(ALL, EPIC, new ShiftIn(List.of("PAY-9003"), 1))).isInstanceOf(AppError.Invalid.class);
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
        assertThat(l.cells()).isNotEmpty(); // its plan in Jira, 40h, over a Total of 0h: nothing is left to plan all the same
        assertThat(l.notYetH()).isZero();
        assertThat(l.plannedH()).isEqualTo(l.totalH());
        assertThat(v.pushBlockers()).noneMatch(b -> b.startsWith("[PAY] PAY-9002"));
        // a Closed step that (by its plan) ends after the next open step starts does not put that step out of order
        issues.save(probe("PAY-9111", StepKind.CREATE, Status.CLOSED, "2026-10-14", "2026-10-15", 8));
        issues.save(probe("PAY-9112", StepKind.BAL_REVIEW, Status.WIP, "2026-10-12", "2026-10-13", 2));
        v = detail.view(ALL, EPIC);
        assertThat(line(v, "PAY-9111").end()).isGreaterThan(line(v, "PAY-9112").start());
        assertThat(line(v, "PAY-9112").outOfOrder()).isFalse();
        assertThat(v.pushBlockers()).noneMatch(b -> b.endsWith("starts before the step before it ends") && b.startsWith("[PAY] PAY-91"));
    }

    /** {@code i} with this Original estimate, Remaining and worklogs. */
    void rewrite(String key, double estimate, double remaining, List<Worklog> logs) {
        Issue i = issues.findByKey(key).orElseThrow();
        issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), i.status(), i.assigneeId(),
                i.plannedStart(), i.plannedEnd(), estimate, remaining, i.created(), i.jiraStatus(), i.functionId(), i.functionName(), i.step(),
                logs, i.events(), i.reporterId(), i.labels()));
    }

    @Test
    void todaysCellCountsNetOfTheHoursLoggedToday() {
        scheduled();
        String key = jiraSubtask();
        rewrite(key, 8, 5, List.of(new Worklog("2026-10-07", "an", 3, ""))); // Jira took today's 3h off Remaining
        planDays.save(key, Map.of("2026-10-07", 4.0, "2026-10-08", 4.0));
        DetailPlanView v = detail.view(ALL, EPIC);
        MatrixLine l = line(v, key);
        assertThat(l.totalH()).isEqualTo(8);
        assertThat(l.plannedH()).isEqualTo(8); // 3h spent, 1h of today's 4h left, 4h tomorrow
        assertThat(l.notYetH()).isZero();
        assertThat(v.pushBlockers()).noneMatch(b -> b.startsWith("[PAY] Export:"));
    }

    @Test
    void savedPlanDaysOutsideJirasDatesAreIgnored() {
        scheduled();
        String key = jiraSubtask(); // 16h, planned 2026-10-06..08 in Jira
        planDays.save(key, Map.of("2026-10-05", 8.0, "2026-10-09", 8.0)); // pushed, then Jira's dates moved
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        assertThat(l.cells()).isNotEmpty().allMatch(c -> c.day().compareTo("2026-10-06") >= 0 && c.day().compareTo("2026-10-08") <= 0);
        assertThat(l.start()).isEqualTo("2026-10-06");
        assertThat(l.end()).isEqualTo("2026-10-08");
    }

    /** A sub-task in Jira of the function {@code fn} under the Task {@code task} (a single ticket when {@code step} is null). */
    void inJira(String key, String task, String fn, @Nullable StepKind step) {
        issues.save(new Issue(key, "PAY", EPIC, task(task), IssueKind.SUBTASK, "[PAY] " + fn + " " + key, "", Status.WIP, "an", "2026-10-06",
                "2026-10-08", 4, 4, "2026-10-01", Status.WIP.value(), step == null ? null : fn, fn, step, List.of(), List.of(), "an", List.of()));
    }

    @Test
    void aRowWhoseStepsAreInJiraShowsThemOnceAndCreatesOnlyTheOthers() {
        long login = staffed().get(0).id();
        inJira("PAY-9151", "ITA Create test case", "Login", StepKind.CREATE);
        inJira("PAY-9152", "ITA Create test case", "Login", StepKind.BAL_REVIEW);
        inJira("PAY-9153", "ITA Execute", "Login", StepKind.FIX_BAL); // another Task: another function
        DetailPlanView v = detail.autoAllocate(ALL, EPIC); // plans the steps not in Jira only
        assertThat(v.lines()).filteredOn(l -> Objects.equals(l.rowId(), login)).extracting(MatrixLine::ref)
                .containsExactly(login + ":fixb", login + ":jmr", login + ":fixj"); // Jira's plan stands for Create and BAL Review
        assertThat(line(v, "PAY-9151").linkedRowId()).isEqualTo(login);
        assertThat(line(v, "PAY-9152").linkedRowId()).isEqualTo(login);
        assertThat(line(v, "PAY-9153").linkedRowId()).isNull();
        assertThat(estimationRows.findByEpic(EPIC).get(0).pushedAt()).isNull(); // three steps are still to create
        // the push creates the row's lines: the three steps not in Jira, planned by Auto allocate
        assertThat(line(v, login + ":fixb").cells()).isNotEmpty();
    }

    @Test
    void aRowWhoseStepsAreAllInJiraIsPushed() {
        List<EstimationRow> rows = scheduled();
        long login = rows.get(0).id();
        for (StepKind k : List.of(StepKind.CREATE, StepKind.BAL_REVIEW, StepKind.FIX_BAL, StepKind.JM_REVIEW, StepKind.FIX_JM)) {
            inJira("PAY-916" + k.ordinal(), "ITA Create test case", "Login", k);
        }
        inJira("PAY-9170", "ITA Execute", "Run", null); // the ticket Run, in Jira under its Task
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.lines()).noneMatch(l -> Objects.equals(l.rowId(), login) || Objects.equals(l.rowId(), rows.get(1).id()));
        assertThat(estimationRows.findByEpic(EPIC)).filteredOn(r -> r.pushedAt() != null).extracting(EstimationRow::id)
                .containsExactlyInAnyOrder(login, rows.get(1).id());
        assertThat(v.problems()).noneMatch(p -> p.contains("(Login)") || p.contains("(Run)"));
    }

    @Test
    void aFunctionInJiraIsItsNameUnderItsTask() {
        scheduled();
        // the same function name under two Tasks (ITA Execute, ITA Create test case): two functions, as the Function Matrix has them
        issues.save(probe("PAY-9141", StepKind.CREATE, Status.WIP, "2026-10-12", "2026-10-13", 16));
        Issue other = probe("PAY-9142", StepKind.BAL_REVIEW, Status.WIP, "2026-10-12", "2026-10-13", 2);
        issues.save(new Issue(other.key(), other.project(), other.epicKey(), task("ITA Create test case"), other.kind(), other.summary(),
                other.phase(), other.status(), other.assigneeId(), other.plannedStart(), other.plannedEnd(), other.estimateH(), other.remainingH(),
                other.created(), other.jiraStatus(), other.functionId(), other.functionName(), other.step(), List.of(), List.of(), "an", List.of()));
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(line(v, "PAY-9142").outOfOrder()).isFalse(); // not a step after PAY-9141's Create
        assertThat(line(v, "PAY-9141").subphase()).isEqualTo("ITA Execute");
        assertThat(line(v, "PAY-9142").subphase()).isEqualTo("ITA Create test case");
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
    void theWindowRunsFromTodayForALateAnken() {
        scheduled("2026-08-03");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        long run = rows.get(1).id();
        assign(rows, new RowAssign(run, "an", null, null));
        // a master schedule ending before today, cells before today, every open sub-task in Jira Closed
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-08-03", List.of("an")), new PhaseRowIn("ITB", "2026-08-10", List.of("an"))),
                "Earlier");
        closeDemoSubtasks();
        cells.saveRowCells(List.of(new Cell(String.valueOf(run), "2026-08-04", 2, false)));
        DetailPlanView v = detail.view(ALL, EPIC);
        TrackerContext ctx = loader.load(ALL);
        List<String> ahead = new java.util.ArrayList<>();
        for (LocalDate d = LocalDate.parse("2026-10-08"); ahead.size() < 20; d = d.plusDays(1)) {
            if (ctx.cal.visible(d.toString())) {
                ahead.add(d.toString());
            }
        }
        assertThat(v.window()).contains("2026-08-04", "2026-10-07").containsAll(ahead);
        assertThat(v.window().getLast()).isEqualTo(ahead.getLast());
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
    void aShiftMovesARowsCellsWorkingDaysLaterAsTyped() {
        List<EstimationRow> rows = scheduled();
        long run = rows.get(1).id();
        assign(rows, new RowAssign(run, "an", null, null));
        String ref = String.valueOf(run);
        cells.saveRowCells(List.of(new Cell(ref, "2026-10-08", 5, true), new Cell(ref, "2026-10-09", 3, true)));
        DetailPlanView v = detail.shift(ALL, EPIC, new ShiftIn(List.of(ref), 2)); // Thu, Fri → Sat (a working day), Mon
        assertThat(line(v, ref).cells()).containsExactly(new DayHours("2026-10-10", 5, false), new DayHours("2026-10-12", 3, false));
        v = detail.shift(ALL, EPIC, new ShiftIn(List.of(ref), -1));
        assertThat(line(v, ref).cells()).containsExactly(new DayHours("2026-10-09", 5, false), new DayHours("2026-10-10", 3, false));
    }

    @Test
    void aShiftCountsTheWorkingDaysOfTheStepsPicFromItsSlot() {
        List<EstimationRow> rows = scheduled();
        long login = rows.get(0).id();
        assign(rows, new RowAssign(login, "an", "minh", "binh"));
        String jmr = login + ":jmr";
        cells.saveRowCells(List.of(new Cell(jmr, "2026-10-12", 2, false)));
        Leave off = calendar.saveLeave(new Leave(0, "binh", "2026-10-13", LeaveType.FULL, "")); // the JM, not the BAL
        try {
            DetailPlanView v = detail.shift(ALL, EPIC, new ShiftIn(List.of(jmr), 1));
            assertThat(line(v, jmr).cells()).containsExactly(new DayHours("2026-10-14", 2, false));
        } finally {
            calendar.deleteLeave(off.id());
        }
    }

    @Test
    void aShiftEarlierBeforeTodayIsRefused() {
        List<EstimationRow> rows = scheduled();
        long run = rows.get(1).id();
        assign(rows, new RowAssign(run, "an", null, null));
        String ref = String.valueOf(run);
        detail.saveCells(ALL, EPIC, List.of(new CellIn(ref, "2026-10-08", 4)));
        assertThatThrownBy(() -> detail.shift(ALL, EPIC, new ShiftIn(List.of(ref), -2)))
                .isInstanceOf(AppError.Invalid.class).hasMessageEndingWith("would move before today").hasFieldOrPropertyWithValue("field", "days");
        assertThatThrownBy(() -> detail.shift(ALL, EPIC, new ShiftIn(List.of(ref), 0)))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "days");
        assertThat(cells.rowCells(EPIC)).containsExactly(new Cell(ref, "2026-10-08", 4, false));
    }

    @Test
    void aShiftOfAJiraLineIsPendingAndKeepsItsPast() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 8.0, "2026-10-07", 4.0, "2026-10-08", 4.0));
        DetailPlanView v = detail.shift(ALL, EPIC, new ShiftIn(List.of(key), 1)); // today on: Wed, Thu → Thu, Fri
        assertThat(line(v, key).cells()).containsExactly(new DayHours("2026-10-06", 8, false), new DayHours("2026-10-08", 4, false),
                new DayHours("2026-10-09", 4, false));
        assertThat(line(v, key).pending()).isTrue();
    }

    @Test
    void aCellOutsideThePlanIsRefused() {
        scheduled();
        String key = jiraSubtask();
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn("999:create", "2026-10-12", 1))))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "ref");
    }

    @Test
    void aPastDayOfAJiraLineIsTypedAndKeptUntilThePush() {
        scheduled();
        String key = jiraSubtask();
        planDays.save(key, Map.of("2026-10-06", 8.0, "2026-10-07", 8.0));
        DetailPlanView v = detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-06", 3)));
        MatrixLine l = line(v, key);
        // the typed past day replaces Jira's; Jira's plan from today on stays
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-06", 3, false), new DayHours("2026-10-07", 8, false));
        assertThat(l.pending()).isTrue();
        l = line(detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-06", 0))), key);
        assertThat(l.cells()).extracting(DayHours::day).containsExactly("2026-10-07"); // 0 clears a past day too
    }

    @Test
    void aCellFarOutsideThePlanOrOverADayIsRefused() {
        List<EstimationRow> rows = scheduled();
        String run = String.valueOf(rows.get(1).id());
        assign(rows, new RowAssign(rows.get(1).id(), "an", null, null));
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn(run, "2027-10-12", 1)))) // past today + 366 days
                .isInstanceOf(AppError.Invalid.class).hasMessage("2027-10-12 is outside the plan").hasFieldOrPropertyWithValue("field", "day");
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn(run, "2026-01-05", 1)))) // before the window
                .isInstanceOf(AppError.Invalid.class).hasMessage("2026-01-05 is outside the plan").hasFieldOrPropertyWithValue("field", "day");
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn(run, "12/10/2026", 1))))
                .isInstanceOf(AppError.Invalid.class).hasFieldOrPropertyWithValue("field", "day");
        assertThatThrownBy(() -> detail.saveCells(ALL, EPIC, List.of(new CellIn(run, "2026-10-12", 24.5))))
                .isInstanceOf(AppError.Invalid.class).hasMessage("At most 24 hours a day").hasFieldOrPropertyWithValue("field", "hours");
        assertThat(cells.rowCells(EPIC)).isEmpty();
        // within today + 366 days, after the window: fine; 24h too
        DetailPlanView v = detail.saveCells(ALL, EPIC, List.of(new CellIn(run, "2027-10-08", 8), new CellIn(run, "2026-10-12", 24)));
        assertThat(line(v, run).cells()).extracting(DayHours::day).containsExactly("2026-10-12", "2027-10-08");
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
    void aJiraLineShowsJirasRemainingForReference() {
        List<EstimationRow> rows = scheduled();
        String key = jiraSubtask();
        Issue i = issues.findByKey(key).orElseThrow();
        issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), i.status(), i.assigneeId(),
                i.plannedStart(), i.plannedEnd(), 16, 9.5, i.created(), i.jiraStatus(), i.functionId(), i.functionName(), i.step(),
                i.worklogs(), i.events(), i.reporterId(), i.labels()));
        DetailPlanView v = detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(20.0, null)); // a pending Total: Jira still says 9.5
        assertThat(line(v, key).actualRemainingH()).isEqualTo(9.5);
        assertThat(line(v, String.valueOf(rows.get(1).id())).actualRemainingH()).isNull(); // not in Jira yet
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
        List<EstimationRow> rows = staffed();
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
        List<EstimationRow> rows = staffed();
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
        staffed();
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
        staffed();
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
    void autoAllocateSkipsAJiraLineItsPeopleHaveNoHoursFor() {
        staffed();
        DetailPlanView before = detail.view(ALL, EPIC);
        MatrixLine chi = line(before, "PAY-1176"); // the demo's: Chi gives PAY-1100 no hours
        assertThat(chi.picId()).isEqualTo("chi");
        assertThat(chi.notYetH()).isPositive();
        MatrixLine an = line(before, "PAY-1180");
        assertThat(an.picId()).isEqualTo("an");
        assertThat(an.notYetH()).isPositive();
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        assertThat(line(v, "PAY-1176").notYetH()).isEqualTo(chi.notYetH());
        assertThat(line(v, "PAY-1176").cells()).isEqualTo(chi.cells());
        assertThat(cells.jiraCells(EPIC)).doesNotContainKey("PAY-1176");
        assertThat(line(v, "PAY-1180").notYetH()).isZero();
        assertThat(v.lines()).filteredOn(l -> l.rowId() != null).allMatch(l -> Math.abs(l.notYetH()) < 0.01);
    }

    @Test
    void autoAllocateTrimsJirasOwnPlanOfAnOverPlannedLine() {
        staffed();
        String key = jiraSubtask(); // planned 2026-10-06..08
        rewrite(key, 8, 2, List.of(new Worklog("2026-10-06", "an", 6, ""))); // 2h left in Jira
        planDays.save(key, Map.of("2026-10-06", 4.0, "2026-10-07", 2.0, "2026-10-08", 2.0)); // 4h from today on
        assertThat(line(detail.view(ALL, EPIC), key).notYetH()).isEqualTo(-2);
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        MatrixLine l = line(v, key);
        assertThat(l.notYetH()).isZero();
        // the latest day goes first; the past stays
        assertThat(l.cells()).containsExactly(new DayHours("2026-10-06", 4, false), new DayHours("2026-10-07", 2, false));
        assertThat(l.pending()).isTrue();
        assertThat(cells.jiraCells(EPIC).get(key)).containsExactlyInAnyOrder(new Cell(key, "2026-10-07", 2, false),
                new Cell(key, "2026-10-08", 0, false));
    }

    @Test
    void autoAllocateTrimsOnlyItsOwnCellsOfAPendingLine() {
        staffed();
        String key = jiraSubtask();
        rewrite(key, 8, 2, List.of(new Worklog("2026-10-06", "an", 6, "")));
        cells.saveJiraCells(EPIC, List.of(new Cell(key, "2026-10-08", 3, false), new Cell(key, "2026-10-09", 2, true))); // 5h for 2h left
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        MatrixLine l = line(v, key);
        // Auto allocate's 2h go; the 3h typed stay, over the total
        assertThat(l.cells()).filteredOn(c -> c.day().compareTo("2026-10-07") >= 0).containsExactly(new DayHours("2026-10-08", 3, false));
        assertThat(l.notYetH()).isEqualTo(-1);
    }

    @Test
    void autoAllocateGivesTheMissingShareOfAJiraStepToItsPic() {
        staffed();
        // a function in Jira with no BAL: its Fix (BA 70 %, BAL 30 %) has An's share only
        issues.save(probe("PAY-9501", StepKind.CREATE, Status.CLOSED, "2026-09-28", "2026-09-29", 8));
        issues.save(probe("PAY-9503", StepKind.FIX_BAL, Status.WIP, "2026-10-01", "2026-10-02", 10));
        assertThat(line(detail.view(ALL, EPIC), "PAY-9503").notYetH()).isEqualTo(10);
        MatrixLine l = line(detail.autoAllocate(ALL, EPIC), "PAY-9503");
        assertThat(l.notYetH()).isZero();
        assertThat(l.cells()).filteredOn(c -> c.day().compareTo("2026-10-07") >= 0).isNotEmpty();
    }

    @Test
    void autoAllocateRefusesARowItsPeopleHaveNoHoursFor() {
        List<EstimationRow> rows = staffed();
        long run = rows.get(1).id();
        assign(rows, new RowAssign(run, "chi", null, null)); // a member of the anken, with no hours on it
        assertThatThrownBy(() -> detail.autoAllocate(ALL, EPIC)).isInstanceOf(AppError.Invalid.class)
                .hasMessageStartingWith("chi has no free hours on PAY-1100").hasFieldOrPropertyWithValue("field", "plan");
        // the people stay; no cell is written
        assertThat(estimationRows.findByEpic(EPIC)).filteredOn(r -> r.id() == run).singleElement()
                .satisfies(r -> assertThat(r.baId()).isEqualTo("chi"));
        assertThat(cells.rowCells(EPIC)).isEmpty();
        assertThat(cells.jiraCells(EPIC)).isEmpty();
    }

    @Test
    void autoAllocateFillsAPartlyTypedStepBeforeTheNextOne() {
        List<EstimationRow> rows = staffed();
        long login = rows.get(0).id();
        detail.saveCells(ALL, EPIC, List.of(new CellIn(login + ":create", "2026-10-14", 4))); // 4h of 8h, on Wednesday
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        List<MatrixLine> lines = v.lines().stream().filter(l -> Objects.equals(l.rowId(), login)).toList();
        assertThat(lines).allMatch(l -> !l.outOfOrder()).allMatch(l -> Math.abs(l.notYetH()) < 0.01);
        assertThat(line(v, login + ":create").cells()).filteredOn(c -> c.day().equals("2026-10-14")).singleElement() // stays typed
                .satisfies(c -> assertThat(c.auto()).isFalse()).satisfies(c -> assertThat(c.hours()).isGreaterThanOrEqualTo(4));
        assertThat(line(v, login + ":balr").start()).isGreaterThanOrEqualTo("2026-10-14");
    }

    @Test
    void autoAllocateFillsAfterATypedStepBetweenOpenOnes() {
        List<EstimationRow> rows = staffed();
        long login = rows.get(0).id();
        detail.saveCells(ALL, EPIC, List.of(new CellIn(login + ":balr", "2026-10-20", 2))); // BAL Review typed in full
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        List<MatrixLine> lines = v.lines().stream().filter(l -> Objects.equals(l.rowId(), login)).toList();
        assertThat(lines).allMatch(l -> !l.outOfOrder()).allMatch(l -> Math.abs(l.notYetH()) < 0.01);
        assertThat(line(v, login + ":balr").cells()).containsExactly(new DayHours("2026-10-20", 2, false));
        assertThat(line(v, login + ":create").end()).isLessThanOrEqualTo("2026-10-20");
        assertThat(line(v, login + ":fixb").start()).isGreaterThanOrEqualTo("2026-10-20");
    }

    @Test
    void autoAllocateGivesAnUnassignedJiraLineToItsPendingPic() {
        staffed();
        issues.save(new Issue("PAY-9201", "PAY", EPIC, task("ITA Execute"), IssueKind.SUBTASK, "[PAY] PAY-9201", "", Status.WIP, null,
                null, null, 6, 6, "2026-10-01", Status.WIP.value(), null, null, null, List.of(), List.of(), "an", List.of()));
        cells.saveJiraEdit(EPIC, new JiraEdit("PAY-9201", null, "binh"));
        assertThat(line(detail.view(ALL, EPIC), "PAY-9201").notYetH()).isEqualTo(6);
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        MatrixLine l = line(v, "PAY-9201");
        assertThat(l.notYetH()).isZero();
        assertThat(l.cells()).isNotEmpty().allMatch(c -> c.day().compareTo("2026-10-07") >= 0);
    }

    @Test
    void autoAllocateSpreadsALongJmReviewEvenly() {
        List<EstimationRow> rows = staffed();
        long login = rows.get(0).id();
        detail.editStep(ALL, EPIC, new StepIn(login, "jmr", 17.0, false)); // more than two days of the JM's working hours
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        MatrixLine jmr = line(v, login + ":jmr");
        assertThat(jmr.notYetH()).isZero();
        List<Double> hours = jmr.cells().stream().map(DayHours::hours).toList();
        assertThat(hours).hasSizeGreaterThanOrEqualTo(3);
        assertThat(jmr.cells()).allMatch(DayHours::auto);
        double each = Math.floor(1700.0 / hours.size()) / 100;
        assertThat(hours.subList(0, hours.size() - 1)).allMatch(h -> h == each);
        assertThat(hours.getLast()).isCloseTo(17 - each * (hours.size() - 1), within(1e-9));
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

    /** Closes the demo's own open sub-tasks of the anken, so that only the lines a test makes can block Push. */
    void closeDemoSubtasks() {
        for (Issue i : issues.findAll()) {
            if (i.kind() == IssueKind.SUBTASK && EPIC.equals(i.epicKey()) && i.status() != Status.CLOSED) {
                issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), Status.CLOSED,
                        i.assigneeId(), i.plannedStart(), i.plannedEnd(), i.estimateH(), 0, i.created(), Status.CLOSED.value(), i.functionId(),
                        i.functionName(), i.step(), i.worklogs(), i.events(), i.reporterId(), i.labels()));
            }
        }
    }

    /** A 3h sub-task in Jira (1h logged, 2h remaining), planned 2026-10-06..08 for An; the demo's own are Closed. */
    String smallSubtask(String ref) {
        String key = tickets.applyBatch(ADMIN, ALL, EPIC, List.of(), List.of(new NewTicket(ref, true, task("ITA Execute"), null, "[PAY] " + ref,
                "an", null, "2026-10-06", "2026-10-08", 3.0, List.of()))).created().get(ref);
        Issue i = issues.findByKey(key).orElseThrow();
        issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), i.status(), i.assigneeId(),
                i.plannedStart(), i.plannedEnd(), 3, 2, i.created(), i.jiraStatus(), i.functionId(), i.functionName(), i.step(),
                List.of(new Worklog("2026-10-06", "an", 1, "")), i.events(), i.reporterId(), i.labels()));
        return key;
    }

    @Test
    void pushRefusedWhileNotYetIsLeft() {
        scheduled();
        closeDemoSubtasks();
        estimation.save(ALL, EPIC, List.of(row(0, "Run", null, 16, task("ITA Execute"))));
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        long run = rows.getFirst().id();
        assign(rows, new RowAssign(run, "an", null, null));
        cells.saveRowCells(List.of(new Cell(String.valueOf(run), "2026-10-12", 8, false), new Cell(String.valueOf(run), "2026-10-13", 6, false)));
        assertThatThrownBy(() -> detail.push(ADMIN, ALL, EPIC)).isInstanceOf(AppError.Invalid.class)
                .hasMessage("[PAY] Run: 2h not planned yet").hasFieldOrPropertyWithValue("field", "plan");
        assertThatThrownBy(() -> detail.pushPreview(ALL, EPIC)).isInstanceOf(AppError.Invalid.class).hasMessage("[PAY] Run: 2h not planned yet");
    }

    @Test
    void aForcedPushIgnoresWhatIsNotPlannedYet() {
        scheduled();
        closeDemoSubtasks();
        estimation.save(ALL, EPIC, List.of(row(0, "Run", null, 16, task("ITA Execute"))));
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        long run = rows.getFirst().id();
        assign(rows, new RowAssign(run, "an", null, null));
        cells.saveRowCells(List.of(new Cell(String.valueOf(run), "2026-10-12", 8, false)));
        assertThat(detail.pushPreview(ALL, EPIC, new PushIn(null, true)).creates()).hasSize(1);
        assertThat(detail.push(ADMIN, ALL, EPIC, new PushIn(null, true)).created()).containsOnlyKeys(String.valueOf(run));
        assertThat(estimationRows.findByEpic(EPIC).getFirst().pushed()).isTrue();
    }

    @Test
    void aForcedPushStillNeedsAPicAndATask() {
        scheduled();
        closeDemoSubtasks();
        estimation.save(ALL, EPIC, List.of(row(0, "Run", null, 16, task("ITA Execute"))));
        assertThatThrownBy(() -> detail.push(ADMIN, ALL, EPIC, new PushIn(null, true))).isInstanceOf(AppError.Invalid.class)
                .hasMessageContaining("pick a member").hasFieldOrPropertyWithValue("field", "plan");
    }

    @Test
    void onlyThePickedLinesArePushedAndCheckedForProblems() {
        scheduled();
        closeDemoSubtasks();
        estimation.save(ALL, EPIC, List.of(row(0, "Run", null, 16, task("ITA Execute")), row(1, "Pay", null, 8, task("ITA Execute"))));
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        String run = String.valueOf(rows.get(0).id());
        assign(rows, new RowAssign(rows.get(0).id(), "an", null, null)); // Pay has no one and no plan
        cells.saveRowCells(List.of(new Cell(run, "2026-10-12", 8, false), new Cell(run, "2026-10-13", 8, false)));
        assertThatThrownBy(() -> detail.push(ADMIN, ALL, EPIC)).isInstanceOf(AppError.Invalid.class);
        assertThat(detail.push(ADMIN, ALL, EPIC, new PushIn(List.of(run), false)).created()).containsOnlyKeys(run);
        assertThat(estimationRows.findByEpic(EPIC)).extracting(EstimationRow::functionName, EstimationRow::pushed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Run", true), org.assertj.core.groups.Tuple.tuple("Pay", false));
    }

    @Test
    void pushingSomeStepsOfAFunctionLeavesItsOtherStepsToPush() {
        List<EstimationRow> rows = staffed();
        closeDemoSubtasks();
        detail.autoAllocate(ALL, EPIC);
        String login = String.valueOf(rows.get(0).id());
        assertThat(detail.push(ADMIN, ALL, EPIC, new PushIn(List.of(login + ":create"), false)).created()).containsOnlyKeys(login + ":create");
        assertThat(estimationRows.findByEpic(EPIC).getFirst().pushed()).isFalse();
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.lines()).filteredOn(l -> l.issueKey() != null && Long.valueOf(rows.get(0).id()).equals(l.linkedRowId()))
                .extracting(MatrixLine::step).containsExactly("create"); // in Jira now, shown in its function
        assertThat(v.lines()).filteredOn(l -> Long.valueOf(rows.get(0).id()).equals(l.rowId())).extracting(MatrixLine::step)
                .containsExactly("balr", "fixb", "jmr", "fixj");
    }

    @Test
    void pushRefusedForAJiraLineToo() {
        scheduled();
        closeDemoSubtasks();
        estimationRows.save(EPIC, List.of()); // only the sub-tasks in Jira are left
        String key = smallSubtask("Export");
        planDays.save(key, Map.of("2026-10-07", 1.0));
        cells.saveJiraEdit(EPIC, new JiraEdit(key, null, "binh"));
        assertThat(line(detail.view(ALL, EPIC), key).notYetH()).isEqualTo(1);
        assertThatThrownBy(() -> detail.push(ADMIN, ALL, EPIC)).isInstanceOf(AppError.Invalid.class)
                .hasMessage("[PAY] Export: 1h not planned yet");
    }

    @Test
    void pushSendsJiraChanges() {
        scheduled();
        closeDemoSubtasks();
        estimationRows.save(EPIC, List.of()); // only the sub-tasks in Jira are left
        String key = smallSubtask("Export");
        planDays.save(key, Map.of("2026-10-06", 1.0, "2026-10-07", 1.0));
        detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(5.0, "binh"));
        detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-08", 2), new CellIn(key, "2026-10-09", 2), new CellIn(key, "2026-10-07", 0)));
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        assertThat(l.notYetH()).isZero();
        assertThat(l.pending()).isTrue();
        assertThat(detail.pushPreview(ALL, EPIC).updates()).singleElement().satisfies(u -> {
            assertThat(u.key()).isEqualTo(key);
            assertThat(u.changes()).extracting(c -> c.field().value()).contains("remaining", "assignee");
        });
        assertThat(detail.push(ADMIN, ALL, EPIC).updated()).containsExactly(key);
        Issue i = issues.findByKey(key).orElseThrow();
        assertThat(i.remainingH()).isEqualTo(4);
        assertThat(i.assigneeId()).isEqualTo("binh");
        assertThat(i.plannedStart()).isEqualTo(l.start());
        assertThat(i.plannedEnd()).isEqualTo("2026-10-09");
        assertThat(i.estimateH()).isEqualTo(3);
        Map<String, Double> days = new TreeMap<>();
        planDays.find(List.of(key)).getOrDefault(key, Map.of()).forEach(days::put);
        assertThat(days).containsExactly(Map.entry("2026-10-06", 1.0), Map.entry("2026-10-08", 2.0), Map.entry("2026-10-09", 2.0));
        assertThat(cells.jiraEdits(EPIC)).isEmpty();
        assertThat(cells.jiraCells(EPIC)).isEmpty();
        assertThat(line(detail.view(ALL, EPIC), key).pending()).isFalse();
    }

    @Test
    void aPicOrTotalEditAloneSendsNoDates() {
        scheduled();
        closeDemoSubtasks();
        estimationRows.save(EPIC, List.of());
        String export = smallSubtask("Export");
        String imports = smallSubtask("Import");
        for (String key : List.of(export, imports)) { // Jira's End moved to Friday 9 since the plan was pushed
            tickets.applyBatch(ADMIN, ALL, EPIC, List.of(new TicketService.ExpectedUpdate(key,
                    List.of(new TicketService.ExpectedChange(tracker.domain.TicketField.PLANNED_END, "2026-10-08", "2026-10-09")))), List.of());
        }
        rewrite(export, 3, 2, List.of(new Worklog("2026-10-06", "an", 1, "")));
        rewrite(imports, 3, 2, List.of(new Worklog("2026-10-06", "an", 1, "")));
        planDays.save(export, Map.of("2026-10-06", 1.0, "2026-10-07", 1.0, "2026-10-08", 1.0));
        planDays.save(imports, Map.of("2026-10-06", 1.0, "2026-10-07", 2.0, "2026-10-08", 2.0));
        detail.editJiraLine(ALL, EPIC, export, new JiraLineIn(null, "binh"));
        detail.editJiraLine(ALL, EPIC, imports, new JiraLineIn(5.0, null)); // Remaining 4h: the 4h planned from today on
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(line(v, export).end()).isEqualTo("2026-10-08");
        assertThat(v.pushBlockers()).isEmpty();
        Map<String, List<String>> fields = new TreeMap<>();
        detail.pushPreview(ALL, EPIC).updates().forEach(u -> fields.put(u.key(), u.changes().stream().map(c -> c.field().value()).toList()));
        assertThat(fields).containsOnly(Map.entry(export, List.of("assignee")), Map.entry(imports, List.of("remaining")));
    }

    @Test
    void pushOnlyJiraChanges() {
        scheduled();
        closeDemoSubtasks();
        estimationRows.save(EPIC, List.of()); // nothing unpushed
        String key = smallSubtask("Export");
        planDays.save(key, Map.of("2026-10-06", 1.0));
        detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-08", 2)));
        assertThat(detail.view(ALL, EPIC).lines()).noneMatch(x -> x.rowId() != null);
        // only its hours per day changed: nothing goes to Jira, the plan is kept
        assertThat(detail.push(ADMIN, ALL, EPIC).updated()).isEmpty();
        assertThat(cells.jiraCells(EPIC)).isEmpty();
        assertThat(cells.jiraEdits(EPIC)).isEmpty();
        assertThat(line(detail.view(ALL, EPIC), key).pending()).isFalse();
        assertThat(planDays.find(List.of(key)).get(key)).containsEntry("2026-10-08", 2.0);
    }

    @Test
    void nothingToPushWithoutRowsOrPendingLines() {
        scheduled();
        closeDemoSubtasks();
        estimationRows.save(EPIC, List.of());
        assertThatThrownBy(() -> detail.push(ADMIN, ALL, EPIC)).isInstanceOf(AppError.Invalid.class).hasMessage("There is nothing to push");
    }

    @Test
    void jiraRefusingHalfWayKeepsWhatWasNotSent(@Autowired tracker.adapters.fake.FakeJira jira, @Autowired JiraWriteLog log, @Autowired SyncService sync,
                                                @Autowired MasterScheduleRepository versions, @Autowired Clock clock, @Autowired AllocationService allocations,
                                                @Autowired OnboardingRepository onboardingRows, @Autowired PlanVersionRepository planVersions,
                                                @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate) {
        scheduled();
        closeDemoSubtasks();
        estimationRows.save(EPIC, List.of());
        String first = smallSubtask("Export");
        String second = smallSubtask("Import");
        for (String key : List.of(first, second)) {
            planDays.save(key, Map.of("2026-10-06", 1.0));
            detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(5.0, null)); // Remaining 4h
            detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-08", 2), new CellIn(key, "2026-10-09", 2)));
        }
        AtomicInteger sent = new AtomicInteger();
        JiraWriter failing = req -> {
            if (sent.incrementAndGet() == 2) { // the first line's one request goes through; the second line's is refused
                throw new JiraRejected(400, "refused for the test");
            }
            return jira.send(req);
        };
        TicketService flakyTickets = new TicketService(loader, new JiraCalls(Optional.of(failing), log, clock), (JiraReader) jira, sync);
        DetailPlanService flaky = new DetailPlanService(loader, estimationRows, versions, allocations, flakyTickets, onboardingRows, estimation,
                planDays, cells, planVersions, (JiraReader) jira, clock, txTemplate);
        assertThatThrownBy(() -> flaky.push(ADMIN, ALL, EPIC)).isInstanceOf(JiraRejected.class);
        assertThat(cells.jiraCells(EPIC)).containsOnlyKeys(second);
        assertThat(planDays.find(List.of(first)).get(first)).containsEntry("2026-10-09", 2.0);
        assertThat(line(detail.view(ALL, EPIC), first).pending()).isFalse();
        assertThat(line(detail.view(ALL, EPIC), second).pending()).isTrue();
    }

    /** {@code real}, except that writing a sub-task's kept plan (after its changes went to Jira) fails like a broken database. */
    static PlanCellRepository failingDeleteJira(PlanCellRepository real) {
        return (PlanCellRepository) java.lang.reflect.Proxy.newProxyInstance(PlanCellRepository.class.getClassLoader(),
                new Class<?>[]{PlanCellRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("deleteJira")) {
                        throw new IllegalStateException("database is down");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Run and Walk (tickets of 8h, An, fully planned) and the sub-task Export in Jira with a pending Remaining and cells. */
    String pushableRowsAndJiraLine() {
        scheduled();
        closeDemoSubtasks();
        estimation.save(ALL, EPIC, List.of(row(0, "Run", null, 8, task("ITA Execute")), row(1, "Walk", null, 8, task("ITA Execute"))));
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        assign(rows, new RowAssign(rows.get(0).id(), "an", null, null), new RowAssign(rows.get(1).id(), "an", null, null));
        cells.saveRowCells(List.of(new Cell(String.valueOf(rows.get(0).id()), "2026-10-12", 8, false),
                new Cell(String.valueOf(rows.get(1).id()), "2026-10-13", 8, false)));
        String key = smallSubtask("Export");
        planDays.save(key, Map.of("2026-10-06", 1.0));
        detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(5.0, null)); // Remaining 4h
        detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-08", 2), new CellIn(key, "2026-10-09", 2)));
        assertThat(detail.view(ALL, EPIC).pushBlockers()).isEmpty();
        return key;
    }

    DetailPlanService serviceWith(JiraWriter writer, PlanCellRepository cellRepo, tracker.adapters.fake.FakeJira jira, JiraWriteLog log, SyncService sync,
                                  MasterScheduleRepository versions, Clock clock, AllocationService allocations, OnboardingRepository onboardingRows,
                                  PlanVersionRepository planVersions, org.springframework.transaction.support.TransactionTemplate txTemplate) {
        TicketService t = new TicketService(loader, new JiraCalls(Optional.of(writer), log, clock), (JiraReader) jira, sync);
        return new DetailPlanService(loader, estimationRows, versions, allocations, t, onboardingRows, estimation, planDays, cellRepo, planVersions,
                (JiraReader) jira, clock, txTemplate);
    }

    @Test
    void aFailingPlanWriteAfterThePushStillMarksTheRowsPushed(@Autowired tracker.adapters.fake.FakeJira jira, @Autowired JiraWriteLog log,
                                                              @Autowired SyncService sync, @Autowired MasterScheduleRepository versions,
                                                              @Autowired Clock clock, @Autowired AllocationService allocations,
                                                              @Autowired OnboardingRepository onboardingRows, @Autowired PlanVersionRepository planVersions,
                                                              @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate) {
        String key = pushableRowsAndJiraLine();
        DetailPlanService broken = serviceWith(jira, failingDeleteJira(cells), jira, log, sync, versions, clock, allocations, onboardingRows,
                planVersions, txTemplate);
        assertThatThrownBy(() -> broken.push(ADMIN, ALL, EPIC)).isInstanceOf(IllegalStateException.class).hasMessage("database is down");
        // both rows' sub-tasks exist in Jira: the rows are pushed, so the next push does not create them again
        assertThat(estimationRows.findByEpic(EPIC)).hasSize(2).allMatch(EstimationRow::pushed);
        // and what was sent was read back from Jira
        assertThat(issues.findByKey(key).orElseThrow().remainingH()).isEqualTo(4);
        // keeping the sub-task's plan is one transaction: its hours per day did not change either, its pending cells stay
        assertThat(planDays.find(List.of(key)).get(key)).containsExactly(Map.entry("2026-10-06", 1.0));
        assertThat(cells.jiraCells(EPIC)).containsKey(key);
    }

    @Test
    void jirasRefusalSurvivesAFailingPlanWrite(@Autowired tracker.adapters.fake.FakeJira jira, @Autowired JiraWriteLog log,
                                               @Autowired SyncService sync, @Autowired MasterScheduleRepository versions,
                                               @Autowired Clock clock, @Autowired AllocationService allocations,
                                               @Autowired OnboardingRepository onboardingRows, @Autowired PlanVersionRepository planVersions,
                                               @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate) {
        pushableRowsAndJiraLine();
        AtomicInteger sent = new AtomicInteger();
        JiraWriter failing = req -> {
            if (sent.incrementAndGet() == 3) { // the update and Run's create go through; Walk's create is refused
                throw new JiraRejected(400, "refused for the test");
            }
            return jira.send(req);
        };
        DetailPlanService broken = serviceWith(failing, failingDeleteJira(cells), jira, log, sync, versions, clock, allocations, onboardingRows,
                planVersions, txTemplate);
        assertThatThrownBy(() -> broken.push(ADMIN, ALL, EPIC)).isInstanceOf(JiraRejected.class).hasMessageContaining("refused for the test")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("database is down"));
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        assertThat(rows.get(0).pushed()).isTrue();
        assertThat(rows.get(1).pushed()).isFalse();
    }

    @Test
    void theLinkerGivesAPushedRowsSavedPlanToItsSubTask(@Autowired tracker.application.service.PlanDayLinker linker,
                                                        @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        scheduled();
        estimation.save(ALL, EPIC, List.of(row(0, "Probe", 5, 8, task("ITA Execute"))));
        long id = estimationRows.findByEpic(EPIC).getFirst().id();
        estimationRows.markPushed(List.of(id), "2026-10-01T00:00:00Z");
        issues.save(probe("PAY-9401", StepKind.CREATE, Status.WIP, "2026-10-12", "2026-10-13", 8));
        issues.save(probe("PAY-9402", StepKind.BAL_REVIEW, Status.WIP, "2026-10-14", "2026-10-14", 2));
        // the plan Auto allocate saved before the push kept it: per person and day
        for (Object[] p : List.<Object[]>of(new Object[]{id + ":create", "an", "2026-10-12", 5.0}, new Object[]{id + ":create", "an", "2026-10-13", 2.0},
                new Object[]{id + ":create", "minh", "2026-10-13", 1.0}, new Object[]{id + ":balr", "minh", "2026-10-14", 2.0})) {
            jdbc.sql("INSERT INTO estimation_plan_parts (row_id, ref, member_id, day, hours) VALUES (?, ?, ?, ?, ?)")
                    .params(id, p[0], p[1], p[2], p[3]).update();
        }
        planDays.save("PAY-9402", Map.of("2026-10-15", 2.0)); // already has hours per day: kept
        linker.linkPushed();
        assertThat(planDays.find(List.of("PAY-9401")).get("PAY-9401")).containsOnly(Map.entry("2026-10-12", 5.0), Map.entry("2026-10-13", 3.0));
        assertThat(planDays.find(List.of("PAY-9402")).get("PAY-9402")).containsOnly(Map.entry("2026-10-15", 2.0));
    }

    @Test
    void theMatrixMigrationCopiesTheSavedPlanOfUnpushedRows(@Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc) throws Exception {
        List<EstimationRow> rows = scheduled();
        long login = rows.get(0).id();
        long pay = rows.get(2).id();
        estimationRows.markPushed(List.of(pay), "2026-10-01T00:00:00Z");
        for (Object[] p : List.<Object[]>of(new Object[]{login, login + ":create", "an", "2026-10-12", 5.0},
                new Object[]{login, login + ":create", "minh", "2026-10-12", 1.25}, new Object[]{login, login + ":create", "an", "2026-10-13", 2.0},
                new Object[]{pay, pay + ":create", "an", "2026-10-19", 8.0})) {
            jdbc.sql("INSERT INTO estimation_plan_parts (row_id, ref, member_id, day, hours) VALUES (?, ?, ?, ?, ?)")
                    .params(p[0], p[1], p[2], p[3], p[4]).update();
        }
        // JM Review counts for nobody: no parts, only where it was placed
        jdbc.sql("""
                INSERT INTO estimation_plan_placed (row_id, ref, phase, step, member_id, hours, planned_start, planned_end, counted)
                VALUES (?, ?, 'ITA', 'jmr', 'tanaka', 1.5, '2026-10-15', '2026-10-16', 0)""").params(login, login + ":jmr").update();
        String sql = new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V19__detail_plan_matrix.sql")).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        List<String> inserts = java.util.Arrays.stream(sql.split(";"))
                .map(st -> st.lines().filter(x -> !x.strip().startsWith("--")).collect(java.util.stream.Collectors.joining("\n")).strip())
                .filter(st -> st.startsWith("INSERT")).toList();
        assertThat(inserts).hasSize(2);
        inserts.forEach(st -> jdbc.sql(st).update());
        assertThat(cells.rowCells(EPIC)).containsExactlyInAnyOrder(new Cell(login + ":create", "2026-10-12", 6.25, true),
                new Cell(login + ":create", "2026-10-13", 2, true), new Cell(login + ":jmr", "2026-10-15", 1.5, true));
    }

    @Test
    void saveVersionStoresTheWholePlan(@Autowired Clock clock, @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc) throws Exception {
        List<EstimationRow> rows = staffed();
        long run = rows.get(1).id();
        detail.saveCells(ALL, EPIC, List.of(new CellIn(String.valueOf(run), "2026-10-12", 8), new CellIn(String.valueOf(run), "2026-10-13", 6)));
        assertThat(detail.view(ALL, EPIC).lastVersionAt()).isNull();
        java.time.Instant before = java.time.Instant.now(clock);
        DetailPlanView v = detail.saveVersion(ADMIN, ALL, EPIC, false);
        java.time.Instant after = java.time.Instant.now(clock);
        assertThat(java.time.Instant.parse(Objects.requireNonNull(v.lastVersionAt()))).isBetween(before, after);
        Map<String, Object> saved = jdbc.queryForMap("SELECT saved_by, body FROM plan_versions WHERE epic_key = ?", EPIC);
        assertThat(saved.get("saved_by")).isEqualTo("Minh");
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) saved.get("body"));
        assertThat(body.get("rows")).hasSize(3);
        assertThat(body.get("rows").get(1).get("id").asLong()).isEqualTo(run);
        assertThat(body.get("rows").get(1).get("ba_id").asText()).isEqualTo("an");
        com.fasterxml.jackson.databind.JsonNode runLine = null;
        for (com.fasterxml.jackson.databind.JsonNode l : body.get("lines")) {
            if (l.get("ref").asText().equals(String.valueOf(run))) {
                runLine = l;
            }
        }
        assertThat(runLine).isNotNull();
        assertThat(runLine.get("pic_id").asText()).isEqualTo("an");
        assertThat(runLine.get("total_h").asDouble()).isEqualTo(16.0);
        assertThat(runLine.get("cells").get("2026-10-12").asDouble()).isEqualTo(8.0);
        assertThat(runLine.get("cells").get("2026-10-13").asDouble()).isEqualTo(6.0);
    }

    /** Only the sub-tasks in Jira: the demo's own Closed, and Export (3h, planned 10/06..08) with nothing pending. */
    String pushedPlan() {
        scheduled();
        closeDemoSubtasks();
        estimationRows.save(EPIC, List.of());
        return smallSubtask("Export");
    }

    @Test
    void aBaselineVersionNeedsEveryLineInJira(@Autowired PlanVersionRepository planVersions) {
        staffed();
        assertThatThrownBy(() -> detail.saveVersion(ADMIN, ALL, EPIC, true)).isInstanceOf(AppError.Invalid.class).hasMessageContaining("not in Jira yet");
        assertThat(planVersions.lastSavedAt(EPIC)).isNull();
    }

    @Test
    void aBaselineVersionNeedsNothingPending() {
        String key = pushedPlan();
        detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(5.0, null));
        assertThatThrownBy(() -> detail.saveVersion(ADMIN, ALL, EPIC, true)).isInstanceOf(AppError.Invalid.class).hasMessageContaining("not pushed");
    }

    @Test
    void aBaselineVersionNeedsJiraToMatch(@Autowired PlanVersionRepository planVersions) {
        String key = pushedPlan();
        Issue i = issues.findByKey(key).orElseThrow(); // 3h in Jira, 10h here
        issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), i.status(), i.assigneeId(),
                i.plannedStart(), i.plannedEnd(), 10, i.remainingH(), i.created(), i.jiraStatus(), i.functionId(), i.functionName(), i.step(),
                i.worklogs(), i.events(), i.reporterId(), i.labels()));
        assertThatThrownBy(() -> detail.saveVersion(ADMIN, ALL, EPIC, true)).isInstanceOf(AppError.Invalid.class)
                .hasMessageContaining(key).hasMessageContaining("Sync");
        assertThat(planVersions.baseline(EPIC)).isEmpty();
    }

    @Test
    void jiraUnreachableStoresNoVersion(@Autowired tracker.adapters.fake.FakeJira jira, @Autowired JiraWriteLog log, @Autowired SyncService sync,
                                        @Autowired MasterScheduleRepository versions, @Autowired Clock clock, @Autowired AllocationService allocations,
                                        @Autowired OnboardingRepository onboardingRows, @Autowired PlanVersionRepository planVersions,
                                        @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate) {
        pushedPlan();
        JiraReader down = (JiraReader) java.lang.reflect.Proxy.newProxyInstance(JiraReader.class.getClassLoader(), new Class<?>[]{JiraReader.class},
                (proxy, method, args) -> {
                    throw new tracker.application.ports.JiraError("Jira cannot be reached");
                });
        TicketService t = new TicketService(loader, new JiraCalls(Optional.of(jira), log, clock), (JiraReader) jira, sync);
        DetailPlanService offline = new DetailPlanService(loader, estimationRows, versions, allocations, t, onboardingRows, estimation, planDays,
                cells, planVersions, down, clock, txTemplate);
        assertThatThrownBy(() -> offline.saveVersion(ADMIN, ALL, EPIC, true)).isInstanceOf(tracker.application.ports.JiraError.class);
        assertThat(planVersions.lastSavedAt(EPIC)).isNull();
    }

    @Test
    void aBaselineVersionKeepsEachSubtasksEstimateParentOwnerAndDays(@Autowired PlanVersionRepository planVersions) {
        String key = pushedPlan();
        detail.saveVersion(ADMIN, ALL, EPIC, true);
        Baseline b = Baselines.of(planVersions.baseline(EPIC)).orElseThrow();
        Issue i = issues.findByKey(key).orElseThrow();
        Baseline.Item export = b.items().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
        assertThat(export.estimateH()).isEqualTo(3);
        assertThat(export.parentKey()).isEqualTo(i.parentKey());
        assertThat(export.ownerId()).isEqualTo("an");
        assertThat(export.days().values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(3);
        assertThat(b.items()).extracting(Baseline.Item::key).doesNotHaveDuplicates()
                .allMatch(k -> !Steps.isJmReview(issues.findByKey(k).orElseThrow()));
        String first = b.savedAt();
        detail.saveVersion(ADMIN, ALL, EPIC, true);
        assertThat(Baselines.of(planVersions.baseline(EPIC)).orElseThrow().savedAt()).isEqualTo(first);
    }

    @Test
    void anOnboardingVersionIsNotABaseline(@Autowired PlanVersionRepository planVersions) {
        staffed();
        detail.saveVersion(ADMIN, ALL, EPIC, false);
        assertThat(planVersions.lastSavedAt(EPIC)).isNotNull();
        assertThat(planVersions.baseline(EPIC)).isEmpty();
    }

    @Test
    void planningFiguresLeaveActualsOut() {
        String key = pushedPlan(); // Original Estimate 3h, 1h logged, Remaining 2h
        detail.editJiraLine(ALL, EPIC, key, new JiraLineIn(6.0, null)); // Remaining 5h: logged + remaining = 6h
        detail.saveCells(ALL, EPIC, List.of(new CellIn(key, "2026-10-08", 1)));
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        assertThat(l.totalH()).isEqualTo(6);
        assertThat(l.planEstimateH()).isEqualTo(3);
        assertThat(l.planH()).isEqualTo(l.cells().stream().mapToDouble(DayHours::hours).sum());
        assertThat(l.planNotYetH()).isEqualTo(3 - l.planH());
    }

    @Test
    void aChangedOriginalEstimateShowsAsNotYetPlan() {
        String key = pushedPlan();
        planDays.save(key, Map.of("2026-10-06", 1.0, "2026-10-07", 1.0, "2026-10-08", 1.0));
        Issue i = issues.findByKey(key).orElseThrow(); // approved: 3h → 30h
        issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), i.status(), i.assigneeId(),
                i.plannedStart(), i.plannedEnd(), 30, i.remainingH(), i.created(), i.jiraStatus(), i.functionId(), i.functionName(), i.step(),
                i.worklogs(), i.events(), i.reporterId(), i.labels()));
        MatrixLine l = line(detail.view(ALL, EPIC), key);
        assertThat(l.planEstimateH()).isEqualTo(30);
        assertThat(l.planH()).isEqualTo(3);
        assertThat(l.planNotYetH()).isEqualTo(27);
    }

    @Test
    void aRowsPlanEstimationIsItsEffort() {
        List<EstimationRow> rows = staffed();
        long run = rows.get(1).id();
        detail.saveCells(ALL, EPIC, List.of(new CellIn(String.valueOf(run), "2026-10-12", 8), new CellIn(String.valueOf(run), "2026-10-13", 6)));
        MatrixLine l = line(detail.view(ALL, EPIC), String.valueOf(run));
        assertThat(l.planEstimateH()).isEqualTo(l.totalH()).isEqualTo(16);
        assertThat(l.planH()).isEqualTo(l.plannedH()).isEqualTo(14);
        assertThat(l.planNotYetH()).isEqualTo(2);
    }

    @Test
    void aBaselineVersionNeedsEveryJiraTicketSynced() {
        String key = pushedPlan();
        issues.deleteAll(List.of(key)); // in Jira, not here yet
        assertThatThrownBy(() -> detail.saveVersion(ADMIN, ALL, EPIC, true)).isInstanceOf(AppError.Invalid.class)
                .hasMessageContaining(key + ": not synced yet");
    }

    @Test
    void aBaselineLeavesOutSubtasksTheBurndownDoesNotDraw(@Autowired PlanVersionRepository planVersions) {
        String dated = pushedPlan();
        String undated = tickets.applyBatch(ADMIN, ALL, EPIC, List.of(), List.of(new NewTicket("Undated", true, task("ITA Execute"), null,
                "[PAY] Undated", "an", null, null, null, 8.0, List.of()))).created().get("Undated");
        detail.saveVersion(ADMIN, ALL, EPIC, true);
        assertThat(Baselines.of(planVersions.baseline(EPIC)).orElseThrow().items()).extracting(Baseline.Item::key)
                .contains(dated).doesNotContain(undated);
    }
}
