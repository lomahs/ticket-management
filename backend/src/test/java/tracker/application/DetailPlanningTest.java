package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.AppError.Invalid;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraRejected;
import tracker.application.ports.JiraWriteLog;
import tracker.application.ports.JiraWriter;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.service.TicketService;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.EstimationService;
import tracker.application.service.EstimationService.EstimationView;
import tracker.application.service.EstimationService.PhaseEffort;
import tracker.application.service.AllocationService;
import tracker.application.service.DetailPlanService;
import tracker.application.service.DetailPlanService.DetailPlanView;
import tracker.application.service.DetailPlanService.DraftIn;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.DetailPlanService.StepPlan;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.MasterScheduleView;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.MasterScheduleService.PhaseShift;
import tracker.domain.AccessStatus;
import tracker.domain.Allocation;
import tracker.domain.Issue;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.MasterSchedule;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.TaskTarget;
import tracker.domain.Viewer;

/** Estimation → master schedule → detail plan → re-plan, on fresh demo data. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DetailPlanningTest {
    static final AnkenScope ALL = new AnkenScope(Set.copyOf(Dataset.TRACKED_EPICS));
    static final String EPIC = "PAY-1100";

    @Autowired
    EstimationService estimation;
    @Autowired
    EstimationRepository estimationRows;
    @Autowired
    MasterScheduleService schedule;
    @Autowired
    AllocationService allocations;
    @Autowired
    TrackerContextLoader loader;
    @Autowired
    DetailPlanService detail;
    @Autowired
    AllocationRepository allocationRows;

    static final Viewer ADMIN = new Viewer("minh.hoang@acme.vn", "Minh", "minh", Role.BAL, true, AccessStatus.ACTIVE,
            Permissions.defaults(Role.BAL));

    void estimate() {
        estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Create test case", "Login", 5, 8),
                row(0, 1, "ITA", "Execute", "Run", null, 16), row(0, 2, "ITB", "Create test case", "Pay", 3, 8)));
    }

    static EstimationRow row(long id, int pos, String phase, String subphase, String fn, Integer steps, double effort) {
        return new EstimationRow(id, EPIC, pos, phase, subphase, fn, steps, effort, null, null, null, null);
    }

    @Test
    void estimationTotalsPerPhaseUseWeightsWithoutJmReview() {
        EstimationView v = estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Create test case", "Login", 5, 8),
                row(0, 1, "ITA", "Execute", "Run", null, 16), row(0, 2, "ITB", "Create test case", "Pay", 3, 8)));
        assertThat(v.rows()).hasSize(3);
        assertThat(v.phases()).containsExactly(new PhaseEffort("ITA", 30.0), new PhaseEffort("ITB", 12.5));
        assertThat(estimation.view(ALL, EPIC).phases()).isEqualTo(v.phases());
    }

    @Test
    void estimationRefusesStepsFour() {
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Create test case", "Login", 4, 8))))
                .isInstanceOf(Invalid.class).hasMessageContaining("3 or 5").extracting("field").isEqualTo("rows.0.steps");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "", "Login", 5, 8))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("rows.0.subphase");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Execute", "Run", null, 0))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("rows.0.effort_h");
    }

    @Test
    void estimationKeepsPushedRowsOutOfReach() {
        EstimationRow saved = estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Create test case", "Login", 5, 8))).rows().get(0);
        estimationRows.markPushed(List.of(saved.id()), "2026-10-07T09:00:00Z");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(saved.id(), 0, "ITA", "Create test case", "Login 2", 5, 8))))
                .isInstanceOf(Invalid.class).hasMessageContaining("pushed");
        EstimationView v = estimation.save(ALL, EPIC, List.of(row(0, 1, "ITA", "Execute", "Run", null, 16)));
        assertThat(v.rows()).extracting(EstimationRow::functionName).containsExactly("Login", "Run");
    }

    @Test
    void masterScheduleComputesEffortEndAndMd() {
        estimate();
        List<PhaseRow> rows = schedule.preview(ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh"))));
        TrackerContext ctx = loader.load(ALL);
        MasterSchedule.PhaseEnd want = MasterSchedule.endOf(EPIC, "2026-10-12", 30, List.of("an", "binh"),
                allocations.counted(ALL).allocations(), ctx.cal);
        assertThat(rows).containsExactly(new PhaseRow("ITA", 30.0, List.of("an", "binh"), "2026-10-12", want.end(), want.planMd()));
        assertThat(want.planMd()).isPositive();
    }

    @Test
    void firstVersionIsTheBaselineAndShiftsCountFromIt() {
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh"))), "Kick-off");
        MasterScheduleView v = schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-14", List.of("an", "binh")),
                new PhaseRowIn("ITB", "2026-10-20", List.of("an"))), "Client moved ITA");
        assertThat(v.versions()).extracting(x -> x.reason()).containsExactly("Kick-off", "Client moved ITA");
        assertThat(v.current().reason()).isEqualTo("Client moved ITA");
        assertThat(v.current().savedBy()).isEqualTo("minh.hoang@acme.vn");
        assertThat(v.estimationPhases()).containsExactly("ITA", "ITB");
        PhaseShift ita = v.shifts().get(0);
        assertThat(ita.phase()).isEqualTo("ITA");
        assertThat(ita.startDays()).isEqualTo(2);
        assertThat(ita.baselineStart()).isEqualTo("2026-10-12");
        assertThat(v.shifts().get(1).baselineStart()).isNull();
        assertThat(v.shifts().get(1).startDays()).isZero();
    }

    @Test
    void masterScheduleNeedsAReasonAndKnownPhasesAndMembers() {
        estimate();
        List<PhaseRowIn> ok = List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an")));
        assertThatThrownBy(() -> schedule.save(ADMIN, ALL, EPIC, ok, " ")).isInstanceOf(Invalid.class).extracting("field").isEqualTo("reason");
        assertThatThrownBy(() -> schedule.preview(ALL, EPIC, List.of(new PhaseRowIn("UAT", "2026-10-12", List.of("an")))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("phases.0.phase");
        assertThatThrownBy(() -> schedule.preview(ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("zz")))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("phases.0.member_ids");
        assertThat(schedule.view(ALL, EPIC).current()).isNull();
    }

    /** Estimation, a master schedule (ITA from Monday 12, ITB from Monday 19) and a full draft. */
    List<EstimationRow> planned() {
        assertThat(loader.load(ALL).ankenMembers.get(EPIC)).contains("an", "binh", "minh", "tanaka");
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("an"))), "Kick-off");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(), List.of(
                new RowAssign(rows.get(0).id(), "an", "minh", "tanaka"), new RowAssign(rows.get(1).id(), "an", null, null),
                new RowAssign(rows.get(2).id(), "binh", "minh", null)), List.of(
                new TaskTarget("ITA", "Create test case", "PAY-1102", null, null), new TaskTarget("ITA", "Execute", null, "ITA Execute", "an"),
                new TaskTarget("ITB", "Create test case", null, "ITB Create test case", "an"))));
        return rows;
    }

    static StepPlan first(DetailPlanView v, long rowId) {
        return v.plan().stream().filter(r -> r.rowId() == rowId).findFirst().orElseThrow().steps().get(0);
    }

    @Test
    void detailPlanSchedulesFromThePhaseStartInRowOrder() {
        List<EstimationRow> rows = planned();
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.problems()).isEmpty();
        assertThat(v.plan()).hasSize(3);
        List<StepPlan> login = v.plan().get(0).steps();
        assertThat(login).extracting(StepPlan::step).containsExactly("create", "balr", "fixb", "jmr", "fixj");
        assertThat(login).extracting(StepPlan::memberId).containsExactly("an", "minh", "an", "tanaka", "an");
        assertThat(login).extracting(StepPlan::hours).containsExactly(8.0, 2.0, 2.5, 2.0, 1.5);
        assertThat(login.get(0).start()).isGreaterThanOrEqualTo("2026-10-12");
        for (int i = 1; i < login.size(); i++) {
            assertThat(login.get(i).start()).isGreaterThan(login.get(i - 1).end());
        }
        assertThat(first(v, rows.get(1).id()).start()).isGreaterThanOrEqualTo(login.get(0).start()); // Run comes after Login for an
        assertThat(first(v, rows.get(2).id()).start()).isGreaterThanOrEqualTo("2026-10-19");
        assertThat(v.tasks()).extracting(t -> t.key()).contains("PAY-1102");
    }

    @Test
    void reorderingRowsChangesTheirDates() {
        List<EstimationRow> rows = planned();
        DetailPlanView v = detail.view(ALL, EPIC);
        DetailPlanView swapped = detail.saveDraft(ALL, EPIC, new DraftIn(List.of(rows.get(1).id(), rows.get(0).id(), rows.get(2).id()),
                List.of(), v.targets()));
        assertThat(swapped.rows().get(0).id()).isEqualTo(rows.get(1).id());
        assertThat(swapped.rows().get(0).baId()).isEqualTo("an"); // assignments are kept
        assertThat(first(swapped, rows.get(1).id()).start()).isLessThanOrEqualTo(first(swapped, rows.get(0).id()).start());
    }

    @Test
    void detailPlanListsProblemsInsteadOfAPlan() {
        estimate();
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.problems()).contains("Save a master schedule first");
        assertThat(v.plan()).isEmpty();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an"))), "Kick-off");
        List<String> p = detail.view(ALL, EPIC).problems();
        assertThat(p).contains("ITB is not in the master schedule", "Row 1 (Login): pick a BA", "Row 2 (Run): pick a member",
                "ITA / Create test case: choose a Task");
    }

    @Test
    void planEndLaterThanMasterEndIsReported() {
        planned();
        DetailPlanView v = detail.view(ALL, EPIC);
        var ita = v.checks().stream().filter(c -> c.phase().equals("ITA")).findFirst().orElseThrow();
        assertThat(ita.planEnd()).isNotNull();
        assertThat(ita.daysLate()).isEqualTo(tracker.domain.Dates.dayDiff(ita.masterEnd(), ita.planEnd()));
        assertThat(ita.daysLate()).isPositive(); // one BA for ITA, the master schedule counted two
    }

    /**
     * Allocations that keep every member within budget: ITA (Login + Run, 30h) by an 8h + minh 1h → 4 days, an given 28h of 32;
     * ITB (Pay, 12.5h) by binh 8h; Login and Run go under a new Task, Pay under the existing PAY-1102.
     */
    List<EstimationRow> pushable() {
        for (String[] a : new String[][]{{"an", "8"}, {"minh", "1"}, {"binh", "8"}}) {
            allocationRows.save(new Allocation(a[0], EPIC, "2026-10-01", Double.parseDouble(a[1])), "test", "2026-10-07T09:00:00Z");
        }
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "minh")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("binh"))), "Kick-off");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(), List.of(
                new RowAssign(rows.get(0).id(), "an", "minh", "tanaka"), new RowAssign(rows.get(1).id(), "an", null, null),
                new RowAssign(rows.get(2).id(), "binh", "an", null)), List.of(
                new TaskTarget("ITA", "Create test case", null, "ITA Create test case", "an"), new TaskTarget("ITA", "Execute", null, "ITA Execute", "an"),
                new TaskTarget("ITB", "Create test case", "PAY-1102", null, null))));
        return rows;
    }

    @Test
    void pushCreatesNewTasksThenSubTasksUnderThem() {
        List<EstimationRow> rows = pushable();
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.overBudget()).isEmpty();
        assertThat(v.overDay()).isEmpty();
        assertThat(detail.pushPreview(ALL, EPIC).creates()).hasSize(2 + 5 + 1 + 3);
        BatchResult r = detail.push(ADMIN, ALL, EPIC);
        TrackerContext ctx = loader.load(ALL);
        String execute = r.created().get("task:ITA/Execute");
        Issue run = ctx.issue(r.created().get(String.valueOf(rows.get(1).id()))).orElseThrow();
        assertThat(run.parentKey()).isEqualTo(execute);
        StepPlan runPlan = first(v, rows.get(1).id());
        assertThat(run.plannedStart()).isEqualTo(runPlan.start());
        assertThat(run.plannedEnd()).isEqualTo(runPlan.end());
        assertThat(run.estimateH()).isEqualTo(16.0);
        assertThat(run.summary()).isEqualTo("[ITA] Run"); // no step and no function id: their brackets and separator go
        assertThat(ctx.issue(r.created().get(rows.get(0).id() + ":create")).orElseThrow().summary()).isEqualTo("[ITA][F01] Login – Create");
        assertThat(ctx.issue(execute).orElseThrow().summary()).isEqualTo("ITA Execute");
        Issue jm = ctx.issue(r.created().get(rows.get(0).id() + ":jmr")).orElseThrow();
        assertThat(jm.estimateH()).isEqualTo(2.0);
        assertThat(jm.parentKey()).isEqualTo(r.created().get("task:ITA/Create test case"));
        Issue pay = ctx.issue(r.created().get(rows.get(2).id() + ":create")).orElseThrow();
        assertThat(pay.parentKey()).isEqualTo("PAY-1102");
        assertThat(estimationRows.findByEpic(EPIC)).allMatch(EstimationRow::pushed);
        assertThat(detail.view(ALL, EPIC).plan()).isEmpty();
    }

    @Test
    void pushIsBlockedOverBudget() {
        planned(); // ITA counted with an and binh, all of it given to an
        assertThat(detail.view(ALL, EPIC).overBudget()).isNotEmpty();
        assertThatThrownBy(() -> detail.pushPreview(ALL, EPIC)).isInstanceOf(Invalid.class).hasMessageContaining("budget");
        assertThatThrownBy(() -> detail.push(ADMIN, ALL, EPIC)).isInstanceOf(Invalid.class);
    }

    @Test
    void rowsCreatedBeforeJiraRefusesStayPushed(@Autowired tracker.adapters.fake.FakeJira jira, @Autowired JiraWriteLog log,
                                                @Autowired SyncService sync, @Autowired MasterScheduleRepository versions, @Autowired Clock clock) {
        List<EstimationRow> rows = pushable();
        AtomicInteger sent = new AtomicInteger();
        JiraWriter failing = req -> {
            if (sent.incrementAndGet() == 9) { // 2 new Tasks, Login's 5 steps and Run go through; Pay's first step is refused
                throw new JiraRejected(400, "refused for the test");
            }
            return jira.send(req);
        };
        TicketService tickets = new TicketService(loader, new JiraCalls(Optional.of(failing), log, clock), (JiraReader) jira, sync);
        DetailPlanService flaky = new DetailPlanService(loader, estimationRows, versions, allocations, tickets, clock);
        assertThatThrownBy(() -> flaky.push(ADMIN, ALL, EPIC)).isInstanceOf(JiraRejected.class);
        Map<Long, Boolean> pushed = new java.util.HashMap<>();
        estimationRows.findByEpic(EPIC).forEach(r -> pushed.put(r.id(), r.pushed()));
        assertThat(pushed).containsEntry(rows.get(0).id(), true).containsEntry(rows.get(1).id(), true).containsEntry(rows.get(2).id(), false);
        assertThat(detail.pushPreview(ALL, EPIC).creates()).hasSize(3); // only Pay's steps are left
    }
}
