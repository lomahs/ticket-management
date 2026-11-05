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
import tracker.application.service.ReplanService;
import tracker.application.service.ReplanService.ReplanIn;
import tracker.application.service.ReplanService.ReplanItem;
import tracker.application.service.ReplanService.ReplanView;
import tracker.application.service.TicketService;
import tracker.application.service.TicketService.BatchPreview;
import tracker.application.service.TicketService.ExpectedChange;
import tracker.application.service.TicketService.ExpectedUpdate;
import tracker.application.service.TicketService.BatchResult;
import tracker.application.service.TicketService.NewTicket;
import tracker.application.service.EstimationService;
import tracker.application.service.EstimationService.EstimationView;
import tracker.application.service.EstimationService.PhaseEffort;
import tracker.application.service.AllocationService;
import tracker.application.service.DetailPlanService;
import tracker.application.service.DetailPlanService.AnkenAllocation;
import tracker.application.service.DetailPlanService.DayLoad;
import tracker.application.service.DetailPlanService.NewRowIn;
import tracker.application.service.DetailPlanService.StepIn;
import tracker.application.service.DetailPlanService.StepRow;
import tracker.application.service.DetailPlanService.OverrunAllocation;
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
    @Autowired
    ReplanService replan;
    @Autowired
    IssueRepository issues;
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
        assertThat(v.tasks()).extracting(t -> t.key()).contains(task("ITA Execute"));
    }

    @Test
    void autoAllocateFillsEmptySlotsWithWhoFinishesSoonestAndKeepsPicks() {
        estimate();
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh", "minh")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("an", "minh"))), "Kick-off");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(),
                List.of(new RowAssign(rows.get(0).id(), "an", null, null))));
        DetailPlanView v = detail.autoAllocate(ALL, EPIC);
        assertThat(v.rows()).extracting(r -> r.functionName() + "=" + r.baId() + "/" + r.balId() + "/" + r.jmId())
                .containsExactly("Login=an/minh/tanaka", // an was picked; the BAL and JM come from the phase team and the anken
                        "Run=binh/null/null", // an is busy with Login, so Bình finishes sooner
                        "Pay=an/minh/null"); // the only BA of the ITB team; a 3-step function has no JM
        assertThat(v.problems()).isEmpty();
        assertThat(v.plan()).hasSize(3);
    }

    @Test
    void autoAllocateNeedsAMasterSchedule() {
        estimate();
        assertThatThrownBy(() -> detail.autoAllocate(ALL, EPIC)).isInstanceOf(Invalid.class).hasMessage("Save a master schedule first");
    }

    @Test
    void reorderingRowsChangesTheirDates() {
        List<EstimationRow> rows = planned();
        DetailPlanView v = detail.view(ALL, EPIC);
        DetailPlanView swapped = detail.saveDraft(ALL, EPIC, new DraftIn(List.of(rows.get(1).id(), rows.get(0).id(), rows.get(2).id()),
                List.of()));
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
        assertThat(p).contains("ITB is not in the master schedule", "Row 1 (Login): pick a BA", "Row 2 (Run): pick a member");
        assertThat(p).noneMatch(x -> x.contains("choose a Task"));
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
     * Allocations that keep every member within budget: ITA (Login + Run, 28h of BA work) by an 8h: the Creates and Run take 3
     * days, Login's reviews 4 more, an given 28h of 56; ITB (Pay, 10.5h) by binh 8h.
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
                new RowAssign(rows.get(2).id(), "binh", "an", null))));
        return rows;
    }

    @Test
    void pushCreatesEachRowsSubTasksUnderItsTask() {
        List<EstimationRow> rows = pushable();
        onboardingRows.save(new Onboarding(EPIC, "", List.of("PAY", "detail"), 6, "2026-10-07T09:00:00Z", "test", "2026-10-07T09:00:00Z", "test", null));
        DetailPlanView v = detail.view(ALL, EPIC);
        assertThat(v.overBudget()).isEmpty();
        assertThat(v.overDay()).isEmpty();
        // the list shows who each sub-task goes to, as the push assigns it (for the CSV export)
        assertThat(v.steps()).filteredOn(st -> st.rowId() == rows.get(0).id()).extracting(st -> st.step() + " " + st.assigneeId() + " " + st.reporterId())
                .containsExactly("create an minh", "balr an null", "fixb minh null", "jmr minh null", "fixj minh null");
        assertThat(detail.pushPreview(ALL, EPIC, false).creates()).hasSize(5 + 1 + 3);
        BatchResult r = detail.push(ADMIN, ALL, EPIC, false);
        TrackerContext ctx = loader.load(ALL);
        String execute = task("ITA Execute");
        Issue run = ctx.issue(r.created().get(String.valueOf(rows.get(1).id()))).orElseThrow();
        assertThat(run.parentKey()).isEqualTo(execute);
        StepPlan runPlan = first(v, rows.get(1).id());
        assertThat(run.plannedStart()).isEqualTo(runPlan.start());
        assertThat(run.plannedEnd()).isEqualTo(runPlan.end());
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
        assertThat(detail.view(ALL, EPIC).plan()).isEmpty();
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
        pushable();
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

    static StepRow step(DetailPlanView v, long rowId, String step) {
        return v.steps().stream().filter(s -> s.rowId() == rowId && step.equals(s.step())).findFirst().orElseThrow();
    }

    @Test
    void theListHasOneLinePerSubTaskWithItsNameAndWhoCarriesIt() {
        List<EstimationRow> rows = pushable();
        DetailPlanView v = detail.view(ALL, EPIC);
        long login = rows.get(0).id();
        assertThat(v.steps().stream().filter(s -> s.rowId() == login)).extracting(StepRow::step).containsExactly("create", "balr", "fixb", "jmr", "fixj");
        assertThat(step(v, login, "create").summary()).isEqualTo("[PAY] Login – Create");
        assertThat(step(v, login, "balr")).extracting(StepRow::baH, StepRow::balH).containsExactly(0.0, 2.0);
        assertThat(step(v, login, "fixb")).extracting(StepRow::baH, StepRow::balH).containsExactly(1.75, 0.75); // 70 / 30 of 2.5h
        assertThat(step(v, login, "jmr")).extracting(StepRow::effortH, StepRow::baH, StepRow::balH).containsExactly(2.0, 0.0, 0.0);
        assertThat(step(v, login, "fixb").start()).isNotNull();
        assertThat(v.days()).extracting(DayLoad::memberId).contains("an", "minh").doesNotContain("tanaka"); // JM Review counts for nobody
        DayLoad first = v.days().stream().filter(d -> d.memberId().equals("an") && d.plannedH() > 0).findFirst().orElseThrow();
        assertThat(first.items()).filteredOn(i -> !i.other()).extracting(DetailPlanService.DayItem::title).contains("[PAY] Login – Create");
        for (DayLoad d : v.days()) { // the day's hours are its items': this anken's, and the other anken's apart
            assertThat(d.items().stream().filter(i -> !i.other()).mapToDouble(DetailPlanService.DayItem::hours).sum()).isCloseTo(d.plannedH(), within(1e-6));
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
        DetailPlanView v = detail.editStep(ALL, EPIC, new StepIn(login, "fixj", null, null, null, true));
        assertThat(step(v, login, "fixj")).extracting(StepRow::removed, StepRow::start).containsExactly(true, null);
        assertThat(v.stalePhases()).containsExactly("ITA"); // its BA effort fell by 70 % of 1.5h
        v = detail.editStep(ALL, EPIC, new StepIn(login, "fixb", 4.0, "2026-10-27", "2026-10-28", false));
        assertThat(step(v, login, "fixb")).extracting(StepRow::effortH, StepRow::edited, StepRow::manual, StepRow::start, StepRow::end)
                .containsExactly(4.0, true, true, "2026-10-27", "2026-10-28");
        assertThat(step(v, login, "jmr").start()).isEqualTo("2026-10-29"); // the next step follows the dates set by hand
        v = detail.editStep(ALL, EPIC, new StepIn(login, "create", 10.0, null, null, false));
        assertThat(v.rows().stream().filter(r -> r.id() == login).findFirst().orElseThrow().effortH()).isEqualTo(10.0);
        assertThat(step(v, login, "create").edited()).isFalse(); // Create's effort is the row's: the other steps follow it
        assertThat(step(v, login, "balr").effortH()).isEqualTo(2.5);
        assertThat(detail.pushPreview(ALL, EPIC, false).creates()).extracting(c -> c.ref()).doesNotContain(login + ":fixj").contains(login + ":fixb");
        assertThatThrownBy(() -> detail.editStep(ALL, EPIC, new StepIn(login, "fixb", null, "2026-10-20", null, false)))
                .isInstanceOf(Invalid.class).hasMessageContaining("both");
        assertThatThrownBy(() -> detail.editStep(ALL, EPIC, new StepIn(rows.get(1).id(), "create", null, null, null, true)))
                .isInstanceOf(Invalid.class).hasMessageContaining("not a step");

        v = detail.addRow(ALL, EPIC, new NewRowIn(task("ITA Execute"), "Export", null, 6));
        assertThat(v.rows()).extracting(EstimationRow::functionName).endsWith("Export");
        assertThat(v.problems()).contains("Row 4 (Export): pick a member");
        assertThatThrownBy(() -> detail.addRow(ALL, EPIC, new NewRowIn(task("ITA Execute"), " ", null, 6))).isInstanceOf(Invalid.class);
    }

    @Test
    void aDayOverTheAllocationIsAWarningThePushGoesOnceConfirmed() {
        List<EstimationRow> rows = pushable();
        DetailPlanView v = detail.editStep(ALL, EPIC, new StepIn(rows.get(1).id(), null, null, "2026-10-12", "2026-10-12", false)); // Run: 16h in a day
        assertThat(v.overDay()).extracting(o -> o.memberId() + " " + o.date()).contains("an 2026-10-12");
        assertThat(v.overBudget()).isEmpty();
        assertThatThrownBy(() -> detail.pushPreview(ALL, EPIC, false)).isInstanceOf(Invalid.class).hasMessageContaining("confirm")
                .extracting("field").isEqualTo("allow_over_day");
        assertThat(detail.pushPreview(ALL, EPIC, true).creates()).isNotEmpty();
        assertThat(detail.push(ADMIN, ALL, EPIC, true).created()).isNotEmpty();
    }

    @Test
    void pushIsBlockedOverBudget() {
        planned(); // ITA counted with an and binh, all of it given to an
        assertThat(detail.view(ALL, EPIC).overBudget()).isNotEmpty();
        assertThatThrownBy(() -> detail.pushPreview(ALL, EPIC, false)).isInstanceOf(Invalid.class).hasMessageContaining("budget");
        assertThatThrownBy(() -> detail.push(ADMIN, ALL, EPIC, false)).isInstanceOf(Invalid.class);
    }

    @Test
    void rowsCreatedBeforeJiraRefusesStayPushed(@Autowired tracker.adapters.fake.FakeJira jira, @Autowired JiraWriteLog log,
                                                @Autowired SyncService sync, @Autowired MasterScheduleRepository versions, @Autowired Clock clock) {
        List<EstimationRow> rows = pushable();
        AtomicInteger sent = new AtomicInteger();
        JiraWriter failing = req -> {
            if (sent.incrementAndGet() == 7) { // Login's 5 steps and Run go through; Pay's first step is refused
                throw new JiraRejected(400, "refused for the test");
            }
            return jira.send(req);
        };
        TicketService tickets = new TicketService(loader, new JiraCalls(Optional.of(failing), log, clock), (JiraReader) jira, sync);
        DetailPlanService flaky = new DetailPlanService(loader, estimationRows, versions, allocations, tickets, onboardingRows, estimation, clock);
        assertThatThrownBy(() -> flaky.push(ADMIN, ALL, EPIC, false)).isInstanceOf(JiraRejected.class);
        Map<Long, Boolean> pushed = new java.util.HashMap<>();
        estimationRows.findByEpic(EPIC).forEach(r -> pushed.put(r.id(), r.pushed()));
        assertThat(pushed).containsEntry(rows.get(0).id(), true).containsEntry(rows.get(1).id(), true).containsEntry(rows.get(2).id(), false);
        assertThat(detail.pushPreview(ALL, EPIC, false).creates()).hasSize(3); // only Pay's steps are left
    }

    /** The pushed plan of {@link #pushable()}: row ref → Jira key. */
    Map<String, String> pushedPlan() {
        pushable();
        return detail.push(ADMIN, ALL, EPIC, false).created();
    }

    void setStatus(String key, Status status, @org.jspecify.annotations.Nullable String plannedStart, double remaining) {
        Issue i = issues.findByKey(key).orElseThrow();
        issues.save(new Issue(i.key(), i.project(), i.epicKey(), i.parentKey(), i.kind(), i.summary(), i.phase(), status, i.assigneeId(),
                plannedStart, i.plannedEnd(), i.estimateH(), remaining, i.created(), i.jiraStatus(), i.functionId(), i.functionName(), i.step(),
                i.worklogs(), i.events(), i.reporterId(), i.labels()));
    }

    static ReplanItem item(ReplanView v, String key) {
        return v.items().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void replanSkipsClosedAndMovesTheRest() {
        Map<String, String> keys = pushedPlan();
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        String create = keys.get(rows.get(0).id() + ":create");
        setStatus(create, Status.CLOSED, "2026-10-12", 0);
        ReplanView v = replan.compute(ALL, EPIC, new ReplanIn("ITA", Map.of()));
        assertThat(v.problems()).isEmpty();
        assertThat(v.items()).extracting(ReplanItem::key).doesNotContain(create).hasSize(5);
        assertThat(v.items()).allMatch(x -> x.toStart().compareTo("2026-10-12") >= 0);
        assertThat(v.check()).isNotNull();
    }

    @Test
    void startedSubTaskKeepsItsPlannedStartAndUsesRemaining() {
        pushedPlan();
        String run = runKey();
        setStatus(run, Status.WIP, "2026-10-12", 6);
        ReplanItem i = item(replan.compute(ALL, EPIC, new ReplanIn("ITA", Map.of())), run);
        assertThat(i.started()).isTrue();
        assertThat(i.hours()).isEqualTo(6.0);
        assertThat(i.toStart()).isEqualTo("2026-10-12");
    }

    @Test
    void resolvedWithNoRemainingEndsOnItsFirstFreeDay() {
        pushedPlan();
        String run = runKey();
        setStatus(run, Status.RESOLVED, "2026-10-12", 0);
        ReplanItem i = item(replan.compute(ALL, EPIC, new ReplanIn("ITA", Map.of())), run);
        assertThat(i.hours()).isZero();
        assertThat(i.toEnd()).isGreaterThanOrEqualTo(i.toStart());
    }

    @Test
    void startedWithoutPlannedStartStartsToday() {
        pushedPlan();
        String run = runKey();
        setStatus(run, Status.WIP, null, 4);
        assertThat(item(replan.compute(ALL, EPIC, new ReplanIn("ITA", Map.of())), run).toStart()).isEqualTo(loader.load(ALL).today);
    }

    @Test
    void reassigningMovesHoursToTheNewMember() {
        pushedPlan();
        String run = runKey();
        ReplanItem i = item(replan.compute(ALL, EPIC, new ReplanIn("ITA", Map.of(run, "binh"))), run);
        assertThat(i.fromMember()).isEqualTo("an");
        assertThat(i.toMember()).isEqualTo("binh");
    }

    @Test
    void replanSharesAStepByTheStepWeightsNotByItsAssignee() {
        Map<String, String> keys = pushedPlan();
        long login = estimationRows.findByEpic(EPIC).get(0).id();
        String fixb = keys.get(login + ":fixb");
        // Fix comment BAL is with Minh (its PIC) but 70 % of it is An's: moving its ticket to Bình (a BA) moves no effort to Bình
        ReplanItem i = item(replan.compute(ALL, EPIC, new ReplanIn("ITA", Map.of(fixb, "binh"))), fixb);
        assertThat(i.fromMember()).isEqualTo("minh");
        assertThat(i.toMember()).isEqualTo("binh");
        TrackerContext ctx = loader.load(ALL);
        assertThat(ctx.planShares(ctx.issue(fixb).orElseThrow())).extracting(sh -> sh.memberId() + " " + sh.fraction())
                .containsExactly("an 0.7", "minh 0.3");
    }

    @Test
    void replanApplyRefusesStaleTargets() {
        pushedPlan();
        String run = runKey();
        ReplanIn in = new ReplanIn("ITA", Map.of(run, "binh"));
        BatchPreview p = replan.preview(ALL, EPIC, in);
        List<ExpectedUpdate> updates = p.updates().stream().map(u -> new ExpectedUpdate(u.key(),
                u.changes().stream().map(c -> new ExpectedChange(c.field(), c.before(), c.after())).toList())).toList();
        assertThat(updates).extracting(ExpectedUpdate::key).contains(run);
        List<ExpectedUpdate> stale = updates.stream().map(u -> !u.key().equals(run) ? u : new ExpectedUpdate(u.key(),
                u.changes().stream().map(c -> new ExpectedChange(c.field(), c.before(), c.field() == tracker.domain.TicketField.ASSIGNEE ? "an" : c.after()))
                        .toList())).toList();
        assertThatThrownBy(() -> replan.apply(ADMIN, ALL, EPIC, in, stale)).isInstanceOf(Invalid.class).hasMessageContaining("review again");
        replan.apply(ADMIN, ALL, EPIC, in, updates);
        assertThat(loader.load(ALL).issue(run).orElseThrow().assigneeId()).isEqualTo("binh");
    }

    String runKey() {
        TrackerContext ctx = loader.load(ALL);
        return ctx.subtasks().stream().filter(i -> i.summary().equals("[PAY] Run")).findFirst().orElseThrow().key();
    }
}
