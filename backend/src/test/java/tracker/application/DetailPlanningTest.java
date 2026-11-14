package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraRejected;
import tracker.application.ports.JiraWriteLog;
import tracker.application.ports.JiraWriter;
import tracker.application.ports.MasterScheduleRepository;
import tracker.application.ports.OnboardingRepository;
import tracker.application.ports.PlanCellRepository;
import tracker.application.ports.PlanCellRepository.Cell;
import tracker.application.ports.PlanDayRepository;
import tracker.application.ports.PlanVersionRepository;
import tracker.application.service.TicketService;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.TicketService.NewTicket;
import tracker.application.service.EstimationService;
import tracker.application.service.EstimationService.EstimationView;
import tracker.application.service.EstimationService.PhaseEffort;
import tracker.application.service.AllocationService;
import tracker.application.service.DetailPlanService;
import tracker.application.service.PlanDayLinker;
import tracker.application.service.DetailPlanService.AnkenAllocation;
import tracker.application.service.DetailPlanService.DayHours;
import tracker.application.service.DetailPlanService.DayLoad;
import tracker.application.service.DetailPlanService.MatrixLine;
import tracker.application.service.DetailPlanService.NewRowIn;
import tracker.application.service.DetailPlanService.StepIn;
import tracker.application.service.DetailPlanService.OverrunAllocation;
import tracker.application.service.DetailPlanService.DetailPlanView;
import tracker.application.service.DetailPlanService.DraftIn;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.MasterScheduleView;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.MasterScheduleService.PhaseShift;
import tracker.domain.AccessStatus;
import tracker.domain.Allocation;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.MasterSchedule;
import tracker.domain.Onboarding;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Viewer;

/** Estimation → master schedule → detail plan → push, on fresh demo data. */
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
    @Autowired
    PlanCellRepository cells;
    @Autowired
    IssueRepository issues;
    @Autowired
    PlanDayRepository planDays;
    @Autowired
    PlanDayLinker linker;
    @Autowired
    TicketService tickets;
    @Autowired
    OnboardingRepository onboardingRows;

    static final Viewer ADMIN = new Viewer("minh.hoang@acme.vn", "Minh", "minh", Role.BAL, true, AccessStatus.ACTIVE,
            Permissions.defaults(Role.BAL));

    /** Tasks created in Jira for the plan, named by the demo's Task pattern "[{anken_prefix}] {sub_phase}": name → key. */
    Map<String, String> tasks = new java.util.HashMap<>();

    String task(String name) {
        return tasks.computeIfAbsent(name, n -> tickets.applyBatch(ADMIN, ALL, EPIC, List.of(),
                List.of(new NewTicket("t", false, null, null, "[PAY] " + n, "an", null, null, null, null, List.of()))).created().get("t"));
    }

    /** Login and Run under new ITA Tasks, Pay under a new ITB Task (ITB Execute makes ITB a phase group: two Tasks share it). */
    void estimate() {
        // the demo's PAY-1176 is Chi's and not fully planned, but Chi gives the anken no hours: Auto allocate could not place it
        allocationRows.save(new Allocation("chi", EPIC, "2026-09-01", 8), "test", "2026-10-07T00:00:00Z");
        task("ITB Execute");
        estimation.save(ALL, EPIC, List.of(row(0, 0, "Login", 5, 8).withTask(task("ITA Create test case")),
                row(0, 1, "Run", null, 16).withTask(task("ITA Execute")),
                row(0, 2, "Pay", 3, 8).withTask(task("ITB Create test case"))));
    }

    record Row(EstimationRow r) {
        EstimationRow withTask(String key) {
            return new EstimationRow(r.id(), r.epicKey(), r.position(), r.phase(), r.subphase(), r.functionName(), r.steps(), r.effortH(),
                    r.baId(), r.balId(), r.jmId(), key, r.pushedAt());
        }
    }

    /** A row as the screen sends it: no phase or subphase, they come from its Task. */
    static Row row(long id, int pos, String fn, Integer steps, double effort) {
        return new Row(new EstimationRow(id, EPIC, pos, "", "", fn, steps, effort, null, null, null, null, null));
    }

    @Test
    void estimationTotalsPerPhaseAreTheBaEffort() {
        estimate();
        EstimationView v = estimation.view(ALL, EPIC);
        assertThat(v.rows()).hasSize(3);
        assertThat(v.rows()).extracting(r -> r.phase() + "/" + r.subphase())
                .containsExactly("ITA/ITA Create test case", "ITA/ITA Execute", "ITB/ITB Create test case");
        // Login: Create 8 + 70 % of Fix BAL 2.5 and Fix JM 1.5, Run 16; Pay: Create 8 + 70 % of Fix BAL 2.5 (the reviews aside)
        assertThat(v.phases()).extracting(PhaseEffort::phase).containsExactly("ITA", "ITB");
        assertThat(v.phases().get(0).totalEffortH()).isCloseTo(26.8, within(1e-9));
        assertThat(v.phases().get(1).totalEffortH()).isCloseTo(9.75, within(1e-9));
        assertThat(v.tasks()).extracting(t -> t.name()).contains("ITA Create test case", "ITA Execute");
    }

    @Test
    void estimationRefusesStepsFourAndRowsWithoutATask() {
        String t = task("ITA Create test case");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "Login", 4, 8).withTask(t))))
                .isInstanceOf(Invalid.class).hasMessageContaining("3 or 5").extracting("field").isEqualTo("rows.0.steps");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "Login", 5, 8).r())))
                .isInstanceOf(Invalid.class).hasMessage("Task is required").extracting("field").isEqualTo("rows.0.task_key");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "Run", null, 0).withTask(t))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("rows.0.effort_h");
        tickets.applyBatch(ADMIN, ALL, EPIC, List.of(), List.of(new NewTicket("t", false, null, null, "Kick-off", "an", null, null, null, null, List.of())));
        String other = loader.load(ALL).parents().stream().filter(p -> p.summary().equals("Kick-off")).findFirst().orElseThrow().key();
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "Login", 5, 8).withTask(other))))
                .isInstanceOf(Invalid.class).hasMessageContaining("Task pattern"); // a Task the Task pattern does not read
    }

    @Test
    void estimationKeepsPushedRowsOutOfReach() {
        String t = task("ITA Create test case");
        EstimationRow saved = estimation.save(ALL, EPIC, List.of(row(0, 0, "Login", 5, 8).withTask(t))).rows().get(0);
        estimationRows.markPushed(List.of(saved.id()), "2026-10-07T09:00:00Z");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(saved.id(), 0, "Login 2", 5, 8).withTask(t))))
                .isInstanceOf(Invalid.class).hasMessageContaining("pushed");
        EstimationView v = estimation.save(ALL, EPIC, List.of(row(0, 1, "Run", null, 16).withTask(task("ITA Execute"))));
        assertThat(v.rows()).extracting(EstimationRow::functionName).containsExactly("Login", "Run");
    }

    @Test
    void masterScheduleComputesEffortEndAndMd() {
        estimate();
        List<PhaseRow> rows = schedule.preview(ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh", "minh"))));
        TrackerContext ctx = loader.load(ALL);
        // Minh is a BAL: the End comes from An's and Bình's hours; the Creates and Run (24h), then Login's 4 review steps
        double effort = rows.get(0).totalEffortH();
        assertThat(effort).isCloseTo(26.8, within(1e-9));
        MasterSchedule.PhaseEnd want = MasterSchedule.endOf(EPIC, "2026-10-12", new MasterSchedule.PhaseWork(effort, 24, 4), List.of("an", "binh"),
                allocations.counted(ALL).allocations(), ctx.cal);
        assertThat(rows).containsExactly(new PhaseRow("ITA", effort, List.of("an", "binh", "minh"), "2026-10-12", want.end(), want.planMd()));
        assertThat(want.planMd()).isPositive();
        assertThatThrownBy(() -> schedule.preview(ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("minh")))))
                .isInstanceOf(Invalid.class).hasMessageContaining("Pick a BA for ITA").extracting("field").isEqualTo("phases.0.member_ids");
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
                new RowAssign(rows.get(2).id(), "binh", "minh", null))));
        assertThat(detail.view(ALL, EPIC).problems()).isEmpty(); // every slot it needs is filled; no cell is planned yet
        return rows;
    }

    static MatrixLine line(DetailPlanView v, String ref) {
        return v.lines().stream().filter(l -> l.ref().equals(ref)).findFirst().orElseThrow();
    }

    /** The lines of a row's steps, in step order. */
    static List<MatrixLine> lines(DetailPlanView v, long rowId) {
        return v.lines().stream().filter(l -> l.rowId() != null && l.rowId() == rowId).toList();
    }

    /** Hours typed into a step's cells: day, hours, day, hours… */
    void plan(String ref, Object... dayHours) {
        List<Cell> out = new java.util.ArrayList<>();
        for (int i = 0; i < dayHours.length; i += 2) {
            out.add(new Cell(ref, (String) dayHours[i], ((Number) dayHours[i + 1]).doubleValue(), false));
        }
        cells.saveRowCells(out);
    }

    /** Every step of Login planned in order on Wednesday 14 to Friday 16: 26.8h of an's (with Run's 16h) and minh's share. */
    void planLogin(long login) {
        plan(login + ":create", "2026-10-14", 8);
        plan(login + ":balr", "2026-10-15", 2);
        plan(login + ":fixb", "2026-10-15", 2.5);
        plan(login + ":jmr", "2026-10-16", 2);
        plan(login + ":fixj", "2026-10-16", 1.5);
    }

    @Test
    void detailPlanListsEachStepOfTheRowsInRowOrder() {
        List<EstimationRow> rows = planned();
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.problems()).isEmpty();
        assertThat(v.lines().stream().filter(l -> l.rowId() != null)).extracting(MatrixLine::rowId)
                .containsExactly(rows.get(0).id(), rows.get(0).id(), rows.get(0).id(), rows.get(0).id(), rows.get(0).id(), rows.get(1).id(),
                        rows.get(2).id(), rows.get(2).id(), rows.get(2).id());
        List<MatrixLine> login = lines(v, rows.get(0).id());
        assertThat(login).extracting(MatrixLine::step).containsExactly("create", "balr", "fixb", "jmr", "fixj");
        assertThat(login).extracting(MatrixLine::picId).containsExactly("an", "an", "minh", "minh", "minh");
        assertThat(login).extracting(MatrixLine::totalH).containsExactly(8.0, 2.0, 2.5, 2.0, 1.5);
        assertThat(login).allMatch(l -> l.cells().isEmpty() && l.start() == null && l.notYetH() == l.totalH()); // nothing planned yet
        assertThat(v.tasks()).extracting(t -> t.key()).contains(task("ITA Execute"));
    }

    @Test
    void autoAllocateFillsEmptySlotsWithWhoFinishesSoonestAndKeepsPicks() {
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh", "minh", "tanaka")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("an", "minh"))), "Kick-off");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(),
                List.of(new RowAssign(rows.get(0).id(), "an", null, null))));
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        assertThat(v.rows()).extracting(r -> r.functionName() + "=" + r.baId() + "/" + r.balId() + "/" + r.jmId())
                .containsExactly("Login=an/minh/tanaka", // an was picked; the BAL and JM come from the phase team
                        "Run=binh/null/null", // an is busy with Login, so Bình finishes sooner
                        "Pay=an/minh/null"); // the only BA of the ITB team; a 3-step function has no JM
        assertThat(v.problems()).isEmpty();
    }

    @Test
    void autoAllocateTakesTheJmFromThePhaseTeamOnly() {
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh", "minh")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("an", "minh"))), "Kick-off");
        // tanaka is a JM of the anken but not in the ITA team: Login's JM stays empty; its BA and BAL are filled
        assertThatThrownBy(() -> detail.autoAllocate(ALL, EPIC)).isInstanceOf(Invalid.class).hasMessage("Row 1 (Login): pick a JM");
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.rows()).filteredOn(r -> r.functionName().equals("Login")).singleElement()
                .satisfies(r -> assertThat(r.jmId()).isNull()).satisfies(r -> assertThat(r.balId()).isEqualTo("minh"));
    }

    @Test
    void autoAllocateRemembersItsPicksSoTheyClearApartFromWhatWasSetByHand() {
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh", "minh", "tanaka")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("an", "minh"))), "Kick-off");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        List<Long> order = rows.stream().map(EstimationRow::id).toList();
        detail.saveDraft(ALL, EPIC, new DraftIn(order, List.of(new RowAssign(rows.get(0).id(), "an", null, null))));
        DetailPlanView v = detail.autoAllocate(ALL, EPIC); // Login=an/minh/tanaka, Run=binh, Pay=an/minh
        assertThat(v.byHand()).extracting(h -> h.rowId() + " " + h.ba() + h.bal() + h.jm()).containsExactly(rows.get(0).id() + " truefalsefalse");
        // Run's member picked by hand (the draft sends every row, the unchanged ones stay Auto allocate's)
        v = detail.saveDraft(ALL, EPIC, new DraftIn(order, v.rows().stream().map(r -> new RowAssign(r.id(),
                r.id() == rows.get(1).id() ? "an" : r.baId(), r.balId(), r.jmId())).toList()));
        assertThat(v.byHand()).extracting(h -> h.rowId()).containsExactly(rows.get(0).id(), rows.get(1).id());

        v = detail.clear(ALL, EPIC, true);
        assertThat(v.rows()).extracting(r -> r.functionName() + "=" + r.baId() + "/" + r.balId() + "/" + r.jmId())
                .containsExactly("Login=an/null/null", "Run=an/null/null", "Pay=null/null/null");
        v = detail.autoAllocate(ALL, EPIC); // fills the slots again around what was set by hand
        assertThat(v.rows()).allMatch(r -> r.baId() != null);
        assertThat(v.rows().get(1).baId()).isEqualTo("an");

        v = detail.clear(ALL, EPIC, false);
        assertThat(v.rows()).allMatch(r -> r.baId() == null && r.balId() == null && r.jmId() == null);
        assertThat(v.byHand()).isEmpty();
        assertThat(estimationRows.autoPicks(EPIC)).isEmpty();
    }

    @Test
    void autoAllocateNeedsAMasterSchedule() {
        estimate();
        assertThatThrownBy(() -> detail.autoAllocate(ALL, EPIC)).isInstanceOf(Invalid.class).hasMessage("Save a master schedule first");
    }

    @Test
    void reorderingRowsReordersTheirLinesAndKeepsTheirCells() {
        List<EstimationRow> rows = planned();
        plan(String.valueOf(rows.get(1).id()), "2026-10-12", 8);
        DetailPlanView swapped = detail.saveDraft(ALL, EPIC, new DraftIn(List.of(rows.get(1).id(), rows.get(0).id(), rows.get(2).id()),
                List.of()));
        assertThat(swapped.rows().get(0).id()).isEqualTo(rows.get(1).id());
        assertThat(swapped.rows().get(0).baId()).isEqualTo("an"); // assignments are kept
        assertThat(swapped.lines().getFirst().ref()).isEqualTo(String.valueOf(rows.get(1).id()));
        assertThat(swapped.lines().getFirst().cells()).containsExactly(new DayHours("2026-10-12", 8, false));
    }

    @Test
    void detailPlanListsProblemsInsteadOfAPlan() {
        estimate();
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.problems()).contains("Save a master schedule first");
        assertThat(v.lines().stream().filter(l -> l.rowId() != null)).allMatch(l -> l.cells().isEmpty());
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an"))), "Kick-off");
        List<String> p = detail.view(ALL, EPIC).problems();
        assertThat(p).contains("ITB is not in the master schedule", "Row 1 (Login): pick a BA", "Row 2 (Run): pick a member");
        assertThat(p).noneMatch(x -> x.contains("choose a Task"));
    }

    @Test
    void planEndLaterThanMasterEndIsReported() {
        List<EstimationRow> rows = planned();
        plan(String.valueOf(rows.get(1).id()), "2026-12-01", 16); // Run, long after ITA's master End
        DetailPlanView v = detail.view(ALL, EPIC);
        var ita = v.checks().stream().filter(c -> c.phase().equals("ITA")).findFirst().orElseThrow();
        assertThat(ita.planEnd()).isEqualTo("2026-12-01");
        assertThat(ita.daysLate()).isEqualTo(tracker.domain.Dates.dayDiff(ita.masterEnd(), ita.planEnd()));
        assertThat(ita.daysLate()).isPositive();
    }

    /**
     * Allocations that keep every member within budget: ITA (Login + Run, 28h of BA work) by an 8h; ITB (Pay, 10.5h) by binh 8h.
     * Run is planned on Monday 12 and Tuesday 13, nothing else yet.
     */
    List<EstimationRow> pushable() {
        List<EstimationRow> rows = unplanned();
        assertThat(detail.view(ALL, EPIC).problems()).isEmpty(); // every slot it needs is filled
        plan(String.valueOf(rows.get(1).id()), "2026-10-12", 8, "2026-10-13", 8);
        return rows;
    }

    /** {@link #pushable} before Auto allocate: the people are picked, nothing is planned. */
    List<EstimationRow> unplanned() {
        for (String[] a : new String[][]{{"an", "8"}, {"minh", "1"}, {"binh", "8"}}) {
            allocationRows.save(new Allocation(a[0], EPIC, "2026-10-01", Double.parseDouble(a[1])), "test", "2026-10-07T09:00:00Z");
        }
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "minh")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("binh"))), "Kick-off");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(), List.of(
                new RowAssign(rows.get(0).id(), "an", "minh", "tanaka"), new RowAssign(rows.get(1).id(), "an", null, null),
                new RowAssign(rows.get(2).id(), "binh", "an", null))));
        return rows;
    }

    @Test
    void pushCreatesEachRowsSubTasksUnderItsTask() {
        List<EstimationRow> rows = pushable();
        onboardingRows.save(new Onboarding(EPIC, "", List.of("PAY", "detail"), 6, "2026-10-07T09:00:00Z", "test", "2026-10-07T09:00:00Z", "test", null));
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.overBudget()).isEmpty();
        // the matrix shows who each sub-task goes to, as the push assigns it (for the CSV export)
        assertThat(lines(v, rows.get(0).id())).extracting(st -> st.step() + " " + st.picId() + " " + st.reporterId())
                .containsExactly("create an minh", "balr an null", "fixb minh null", "jmr minh null", "fixj minh null");
        assertThat(detail.pushPreview(ALL, EPIC).creates()).hasSize(5 + 1 + 3);
        BatchResult r = detail.push(ADMIN, ALL, EPIC);
        TrackerContext ctx = loader.load(ALL);
        String execute = task("ITA Execute");
        Issue run = ctx.issue(r.created().get(String.valueOf(rows.get(1).id()))).orElseThrow();
        assertThat(run.parentKey()).isEqualTo(execute);
        assertThat(run.plannedStart()).isEqualTo("2026-10-12"); // the first and last day with hours
        assertThat(run.plannedEnd()).isEqualTo("2026-10-13");
        assertThat(run.estimateH()).isEqualTo(16.0);
        assertThat(run.labels()).containsExactly("PAY", "detail"); // the labels entered at the onboarding's Setting Phase
        // no template: the sub-phase prefix is the Task's bracket; no step: the separator before it goes
        assertThat(run.summary()).isEqualTo("[PAY] Run");
        assertThat(ctx.issue(r.created().get(rows.get(0).id() + ":create")).orElseThrow().summary()).isEqualTo("[PAY] Login – Create");
        Issue jm = ctx.issue(r.created().get(rows.get(0).id() + ":jmr")).orElseThrow();
        // assigned by the PIC of Settings › Step weights: Create and BAL Review to the BA, the others to the BAL
        Issue create = ctx.issue(r.created().get(rows.get(0).id() + ":create")).orElseThrow();
        assertThat(create.assigneeId()).isEqualTo("an");
        assertThat(create.reporterId()).isEqualTo("minh"); // so the function's BAL is read back from Jira
        assertThat(ctx.issue(r.created().get(rows.get(0).id() + ":balr")).orElseThrow().assigneeId()).isEqualTo("an");
        assertThat(ctx.issue(r.created().get(rows.get(0).id() + ":fixb")).orElseThrow().assigneeId()).isEqualTo("minh");
        assertThat(jm.assigneeId()).isEqualTo("minh");
        assertThat(jm.estimateH()).isEqualTo(2.0);
        assertThat(jm.parentKey()).isEqualTo(task("ITA Create test case"));
        Issue pay = ctx.issue(r.created().get(rows.get(2).id() + ":create")).orElseThrow();
        assertThat(pay.parentKey()).isEqualTo(task("ITB Create test case"));
        assertThat(estimationRows.findByEpic(EPIC)).allMatch(EstimationRow::pushed);
        assertThat(detail.view(ALL, EPIC).lines()).allMatch(l -> l.rowId() == null);
        // each sub-task keeps the plan's hours per day, which its plan remain uses
        Map<String, Double> days = planDays.find(List.of(run.key())).get(run.key());
        assertThat(days).containsExactly(Map.entry("2026-10-12", 8.0), Map.entry("2026-10-13", 8.0));
        assertThat(loader.load(ALL).planDays(run)).isEqualTo(days);
        // a push made before the hours were kept is linked by its Task, function name and step
        Map<String, Double> createDays = planDays.find(List.of(create.key())).get(create.key());
        planDays.save(create.key(), Map.of());
        linker.linkPushed();
        assertThat(planDays.find(List.of(create.key())).get(create.key())).isEqualTo(createDays);
    }

    /** A 40h sub-task in Jira under ITA Execute. */
    Issue subtask(String key, String who, Status status) {
        return new Issue(key, "PAY", EPIC, task("ITA Execute"), IssueKind.SUBTASK, "[PAY] " + key, "", status, who, "2026-10-12", "2026-10-16",
                40, status == Status.CLOSED ? 0 : 40, "2026-10-07", status.value(), null, null, null, List.of(), List.of(), who, List.of());
    }

    @Test
    void onlyMembersOfTheAnkenHaveABudget() {
        pushable();
        issues.save(subtask("PAY-9001", "nhan", Status.WIP)); // Nhân is not a member of PAY-1100: it uses nobody's budget there
        assertThat(detail.view(ALL, EPIC).overBudget()).isEmpty();
    }

    @Test
    void closedSubTasksUseNoBudgetAndAnOverrunShowsTheAllocations() {
        planLogin(pushable().get(0).id());
        issues.save(subtask("PAY-9002", "an", Status.CLOSED));
        assertThat(detail.view(ALL, EPIC).overBudget()).isEmpty();
        issues.save(subtask("PAY-9003", "an", Status.WIP)); // an: 26.8h planned (70 % of the Fix steps) + 40h open in Jira
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.overBudget()).extracting(o -> o.memberId() + "|" + o.phase() + "|" + o.plannedH()).containsExactly("an|ITA|66.8");
        assertThat(v.allocated()).hasSize(1);
        OverrunAllocation al = v.allocated().get(0);
        assertThat(al.memberId()).isEqualTo("an");
        assertThat(al.date()).isEqualTo("2026-10-12"); // the phase's Start
        assertThat(al.ankens().get(0)).isEqualTo(new AnkenAllocation(EPIC, loader.load(ALL).anken(EPIC).orElseThrow().title(), 8.0));
        assertThat(al.ankens().subList(1, al.ankens().size())).allMatch(x -> !x.epicKey().equals(EPIC) && x.hoursPerDay() > 0);
    }

    @Test
    void theMatrixHasOneLinePerSubTaskWithItsNameAndWhoCarriesIt() {
        List<EstimationRow> rows = pushable();
        long login = rows.get(0).id();
        plan(login + ":create", "2026-10-14", 8);
        plan(login + ":fixb", "2026-10-15", 2.5);
        plan(login + ":jmr", "2026-10-16", 2);
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(lines(v, login)).extracting(MatrixLine::step).containsExactly("create", "balr", "fixb", "jmr", "fixj");
        assertThat(line(v, login + ":create").summary()).isEqualTo("[PAY] Login – Create");
        assertThat(line(v, login + ":jmr").totalH()).isEqualTo(2.0);
        assertThat(hoursOf(v, line(v, login + ":fixb").summary())).containsOnlyKeys("an 2026-10-15", "minh 2026-10-15"); // 70 / 30
        assertThat(v.days()).extracting(DayLoad::memberId).contains("an", "minh");
        assertThat(hoursOf(v, line(v, login + ":jmr").summary())).isEmpty(); // JM Review counts for nobody
        DayLoad first = v.days().stream().filter(d -> d.memberId().equals("an") && d.date().equals("2026-10-14")).findFirst().orElseThrow();
        assertThat(first.items()).filteredOn(i -> !i.other()).extracting(DetailPlanService.DayItem::title).contains("[PAY] Login – Create");
        for (DayLoad d : v.days()) { // the day's hours are its items': this anken's, and the other anken's apart
            assertThat(d.items().stream().filter(i -> !i.other()).mapToDouble(DetailPlanService.DayItem::hours).sum()).isCloseTo(d.plannedH(), within(0.05));
            assertThat(d.items().stream().filter(DetailPlanService.DayItem::other).mapToDouble(DetailPlanService.DayItem::hours).sum())
                    .isCloseTo(d.otherH(), within(1e-6));
        }
        assertThat(v.days()).flatExtracting(DayLoad::items).filteredOn(DetailPlanService.DayItem::other)
                .allMatch(i -> i.key() != null && !i.key().equals(EPIC));
        assertThat(v.stalePhases()).isEmpty();
    }

    @Test
    void stepsCanBeEditedRemovedAndAdded() {
        List<EstimationRow> rows = pushable();
        long login = rows.get(0).id();
        DetailPlanView v = detail.editStep(ALL, EPIC, new StepIn(login, "fixj", null, true));
        assertThat(line(v, login + ":fixj").removed()).isTrue();
        assertThat(v.stalePhases()).containsExactly("ITA"); // its BA effort fell by 70 % of 1.5h
        v = detail.editStep(ALL, EPIC, new StepIn(login, "fixb", 4.0, false));
        assertThat(line(v, login + ":fixb")).extracting(MatrixLine::totalH, MatrixLine::edited).containsExactly(4.0, true);
        v = detail.editStep(ALL, EPIC, new StepIn(login, "create", 10.0, false));
        assertThat(v.rows().stream().filter(r -> r.id() == login).findFirst().orElseThrow().effortH()).isEqualTo(10.0);
        assertThat(line(v, login + ":create").edited()).isFalse(); // Create's effort is the row's: the other steps follow it
        assertThat(line(v, login + ":balr").totalH()).isEqualTo(2.5);
        assertThat(detail.pushPreview(ALL, EPIC).creates()).extracting(c -> c.ref()).doesNotContain(login + ":fixj").contains(login + ":fixb");
        assertThatThrownBy(() -> detail.editStep(ALL, EPIC, new StepIn(rows.get(1).id(), "create", null, true)))
                .isInstanceOf(Invalid.class).hasMessageContaining("not a step");

        v = detail.addRow(ALL, EPIC, new NewRowIn(task("ITA Execute"), "Export", null, 6));
        assertThat(v.rows()).extracting(EstimationRow::functionName).endsWith("Export");
        assertThat(v.problems()).contains("Row 4 (Export): pick a member");
        assertThatThrownBy(() -> detail.addRow(ALL, EPIC, new NewRowIn(task("ITA Execute"), " ", null, 6))).isInstanceOf(Invalid.class);
    }

    @Test
    void pickingAPersonKeepsTheCellsAndMovesTheirHours() {
        List<EstimationRow> rows = pushable();
        String run = String.valueOf(rows.get(1).id());
        DetailPlanView before = detail.view(ALL, EPIC);
        DetailPlanView v = detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(),
                List.of(new RowAssign(rows.get(1).id(), "binh", null, null))));
        assertThat(v.rows().get(1).baId()).isEqualTo("binh");
        assertThat(line(v, run).cells()).isEqualTo(line(before, run).cells());
        assertThat(hoursOf(v, "[PAY] Run")).containsExactly(Map.entry("binh 2026-10-12", 8.0), Map.entry("binh 2026-10-13", 8.0));
    }

    @Test
    void aStepRemovedLeavesThePlan() {
        List<EstimationRow> rows = pushable();
        long login = rows.get(0).id();
        plan(login + ":create", "2026-10-14", 8);
        plan(login + ":fixj", "2026-10-15", 1.5);
        DetailPlanView v = detail.editStep(ALL, EPIC, new StepIn(login, "fixj", null, true));
        assertThat(lines(v, login)).filteredOn(l -> !l.removed()).extracting(MatrixLine::step).containsExactly("create", "balr", "fixb", "jmr");
        assertThat(v.days()).flatExtracting(DayLoad::items).extracting(DetailPlanService.DayItem::title)
                .contains("[PAY] Login – Create").noneMatch(t -> t.startsWith("[PAY] Login – Fix comment JM"));
    }

    @Test
    void autoAllocateThatCannotFillKeepsThePlan() {
        List<EstimationRow> rows = pushable();
        DetailPlanView before = detail.view(ALL, EPIC);
        // the ITA team has no JM: Auto allocate cannot fill Login's again
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(), List.of(new RowAssign(rows.get(0).id(), "an", "minh", null))));
        assertThatThrownBy(() -> detail.autoAllocate(ALL, EPIC)).isInstanceOf(Invalid.class).hasMessage("Row 1 (Login): pick a JM");
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.rows().get(0).jmId()).isNull();
        assertThat(v.lines()).extracting(MatrixLine::cells).isEqualTo(before.lines().stream().map(MatrixLine::cells).toList());
    }

    @Test
    void aNewRowHasNoCells() {
        pushable();
        DetailPlanView v = detail.addRow(ALL, EPIC, new NewRowIn(task("ITA Execute"), "Export", null, 6));
        long export = v.rows().getLast().id();
        assertThat(lines(v, export)).singleElement().satisfies(l -> {
            assertThat(l.cells()).isEmpty();
            assertThat(l.start()).isNull();
            assertThat(l.notYetH()).isEqualTo(6.0);
        });
    }

    /** Hours per member and day of one sub-task in the Effort per day items. */
    static Map<String, Double> hoursOf(DetailPlanView v, String title) {
        Map<String, Double> out = new java.util.TreeMap<>();
        v.days().forEach(d -> d.items().stream().filter(i -> !i.other() && i.title().equals(title))
                .forEach(i -> out.merge(d.memberId() + " " + d.date(), i.hours(), Double::sum)));
        return out;
    }

    @Test
    void aDayOverTheAllocationOnlyWarns() {
        List<EstimationRow> rows = pushable();
        plan(String.valueOf(rows.get(1).id()), "2026-10-12", 16, "2026-10-13", 0); // Run: 16h in a day
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.overDay()).extracting(o -> o.memberId() + " " + o.date()).contains("an 2026-10-12");
        assertThat(v.overBudget()).isEmpty();
        assertThat(detail.pushPreview(ALL, EPIC).creates()).isNotEmpty();
        assertThat(detail.push(ADMIN, ALL, EPIC).created()).isNotEmpty();
    }

    @Test
    void overBudgetOnlyWarns() {
        List<EstimationRow> rows = planned(); // ITA counted with an and binh, all of it given to an
        plan(String.valueOf(rows.get(1).id()), "2026-10-12", 8, "2026-10-13", 8);
        planLogin(rows.get(0).id());
        assertThat(detail.view(ALL, EPIC).overBudget()).isNotEmpty();
        assertThat(detail.pushPreview(ALL, EPIC).creates()).isNotEmpty();
        assertThat(detail.push(ADMIN, ALL, EPIC).created()).isNotEmpty();
    }

    @Test
    void inputProblemsStillBlockThePush() {
        List<EstimationRow> rows = pushable();
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(), List.of(new RowAssign(rows.get(1).id(), null, null, null))));
        assertThatThrownBy(() -> detail.pushPreview(ALL, EPIC)).isInstanceOf(Invalid.class).hasMessage("Row 2 (Run): pick a member");
    }

    @Test
    void pushSendsTheDaysOfTheCells() {
        List<EstimationRow> rows = pushable();
        long login = rows.get(0).id();
        plan(login + ":create", "2026-10-14", 4, "2026-10-15", 4);
        plan(login + ":fixb", "2026-10-27", 1, "2026-10-28", 1.5);
        DetailPlanView v = detail.addRow(ALL, EPIC, new NewRowIn(task("ITA Execute"), "Export", null, 6));
        long export = v.rows().getLast().id();
        detail.saveDraft(ALL, EPIC, new DraftIn(v.rows().stream().filter(r -> !r.pushed()).map(EstimationRow::id).toList(),
                List.of(new RowAssign(export, "an", null, null))));
        BatchResult r = detail.push(ADMIN, ALL, EPIC);
        TrackerContext ctx = loader.load(ALL);
        Issue fixb = ctx.issue(r.created().get(login + ":fixb")).orElseThrow();
        assertThat(List.of(fixb.plannedStart(), fixb.plannedEnd())).containsExactly("2026-10-27", "2026-10-28");
        assertThat(planDays.find(List.of(fixb.key())).get(fixb.key())).containsExactly(Map.entry("2026-10-27", 1.0), Map.entry("2026-10-28", 1.5));
        Issue create = ctx.issue(r.created().get(login + ":create")).orElseThrow();
        assertThat(List.of(create.plannedStart(), create.plannedEnd())).containsExactly("2026-10-14", "2026-10-15");
        Issue ex = ctx.issue(r.created().get(String.valueOf(export))).orElseThrow();
        assertThat(ex.plannedStart()).isNull(); // no cells yet: no dates
        assertThat(ex.plannedEnd()).isNull();
    }

    @Test
    void rowsCreatedBeforeJiraRefusesStayPushed(@Autowired tracker.adapters.fake.FakeJira jira, @Autowired JiraWriteLog log,
                                                @Autowired SyncService sync, @Autowired MasterScheduleRepository versions, @Autowired Clock clock,
                                                @Autowired PlanVersionRepository planVersions) {
        List<EstimationRow> rows = pushable();
        AtomicInteger sent = new AtomicInteger();
        JiraWriter failing = req -> {
            if (sent.incrementAndGet() == 7) { // Login's 5 steps and Run go through; Pay's first step is refused
                throw new JiraRejected(400, "refused for the test");
            }
            return jira.send(req);
        };
        TicketService tickets = new TicketService(loader, new JiraCalls(Optional.of(failing), log, clock), (JiraReader) jira, sync);
        DetailPlanService flaky = new DetailPlanService(loader, estimationRows, versions, allocations, tickets, onboardingRows, estimation, planDays,
                cells, planVersions, clock);
        assertThatThrownBy(() -> flaky.push(ADMIN, ALL, EPIC)).isInstanceOf(JiraRejected.class);
        Map<Long, Boolean> pushed = new java.util.HashMap<>();
        estimationRows.findByEpic(EPIC).forEach(r -> pushed.put(r.id(), r.pushed()));
        assertThat(pushed).containsEntry(rows.get(0).id(), true).containsEntry(rows.get(1).id(), true).containsEntry(rows.get(2).id(), false);
        assertThat(detail.pushPreview(ALL, EPIC).creates()).hasSize(3); // only Pay's steps are left
    }
}
