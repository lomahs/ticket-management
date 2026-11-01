package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.AppError.Invalid;
import tracker.application.ports.EstimationRepository;
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
}
