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
}
