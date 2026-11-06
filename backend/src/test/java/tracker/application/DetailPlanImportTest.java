package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.AppError.Invalid;
import tracker.application.ports.EstimationRepository;
import tracker.application.service.DetailPlanImportService;
import tracker.application.service.DetailPlanImportService.ImportChange;
import tracker.application.service.DetailPlanImportService.ImportLine;
import tracker.application.service.DetailPlanImportService.ImportLineIn;
import tracker.application.service.DetailPlanImportService.Sheet;
import tracker.application.service.DetailPlanImportService.SheetLine;
import tracker.application.service.DetailPlanService;
import tracker.application.service.DetailPlanService.DetailPlanView;
import tracker.application.service.DetailPlanService.DraftIn;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.DetailPlanService.StepRow;
import tracker.application.service.EstimationService;
import tracker.application.service.MasterScheduleService;
import tracker.application.service.MasterScheduleService.PhaseRowIn;
import tracker.application.service.TicketService;
import tracker.application.service.TicketService.NewTicket;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.Permissions;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Detail plan › Download template and Import: the sheet, the preview of a filled one, and applying it. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DetailPlanImportTest {
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
    DetailPlanImportService imports;
    @Autowired
    TicketService tickets;

    final Map<String, String> tasks = new java.util.HashMap<>();

    String task(String name) {
        return tasks.computeIfAbsent(name, n -> tickets.applyBatch(ADMIN, ALL, EPIC, List.of(),
                List.of(new NewTicket("t", false, null, null, "[PAY] " + n, "an", null, null, null, null, List.of()))).created().get("t"));
    }

    static EstimationRow row(int pos, String fn, Integer steps, double effort, String task) {
        return new EstimationRow(0, EPIC, pos, "", "", fn, steps, effort, null, null, null, task, null);
    }

    /** Login (5 steps) and Run (a ticket) in ITA, Pay (3 steps) in ITB; Login is given An, Minh and Tanaka, Run An, Pay Bình and Minh. */
    List<EstimationRow> planned() {
        task("ITB Execute");
        estimation.save(ALL, EPIC, List.of(row(0, "Login", 5, 8, task("ITA Create test case")), row(1, "Run", null, 16, task("ITA Execute")),
                row(2, "Pay", 3, 8, task("ITB Create test case"))));
        schedule.save(ADMIN, ALL, EPIC, List.of(new PhaseRowIn("ITA", "2026-10-12", List.of("an", "binh", "minh", "tanaka")),
                new PhaseRowIn("ITB", "2026-10-19", List.of("an", "minh"))), "Kick-off");
        List<EstimationRow> rows = estimationRows.findByEpic(EPIC);
        detail.saveDraft(ALL, EPIC, new DraftIn(rows.stream().map(EstimationRow::id).toList(), List.of(
                new RowAssign(rows.get(0).id(), "an", "minh", "tanaka"), new RowAssign(rows.get(1).id(), "an", null, null),
                new RowAssign(rows.get(2).id(), "binh", "minh", null))));
        return rows;
    }

    /** The sheet's lines as cells, the way the file is read back (header left out). */
    static List<List<String>> cells(Sheet s) {
        List<List<String>> out = new ArrayList<>();
        for (SheetLine l : s.lines()) {
            out.add(new ArrayList<>(List.of(l.ref(), l.phase(), l.function(), l.step(), l.summary(), l.ba(), l.bal(), l.jm(),
                    tracker.domain.Numbers.format(l.effortH()), l.start() == null ? "" : l.start(), l.end() == null ? "" : l.end())));
        }
        return out;
    }

    static StepRow step(DetailPlanView v, String ref) {
        return v.steps().stream().filter(s -> s.ref().equals(ref)).findFirst().orElseThrow();
    }

    @Test
    void theSheetListsEverySubTaskWithItsPeopleAndTheAnkenMembers() {
        List<EstimationRow> rows = planned();
        Sheet s = imports.sheet(ALL, EPIC);
        long login = rows.get(0).id();
        assertThat(s.lines()).extracting(SheetLine::ref).startsWith(login + ":create", login + ":balr", login + ":fixb", login + ":jmr",
                login + ":fixj", String.valueOf(rows.get(1).id()));
        SheetLine create = s.lines().get(0);
        assertThat(List.of(create.phase(), create.function(), create.step(), create.ba(), create.bal(), create.jm()))
                .containsExactly("ITA", "Login", "Create", "An", "Minh", "Tanaka");
        assertThat(create.start()).isNotNull();
        SheetLine run = s.lines().get(5);
        assertThat(List.of(run.step(), run.ba(), run.bal(), run.jm())).containsExactly("Ticket", "An", "", "");
        assertThat(s.people()).extracting(DetailPlanImportService.SheetPerson::name).contains("An", "Bình", "Minh", "Tanaka");
    }

    @Test
    void theSheetAsItIsChangesNothing() {
        planned();
        List<ImportLine> lines = imports.preview(ALL, EPIC, cells(imports.sheet(ALL, EPIC)));
        assertThat(lines).allSatisfy(l -> assertThat(l.errors()).isEmpty());
        assertThat(lines).allSatisfy(l -> assertThat(l.changes()).isEmpty()); // the computed dates are not set by hand
    }

    @Test
    void previewListsWhatChangesAndWhatIsWrong() {
        List<EstimationRow> rows = planned();
        long login = rows.get(0).id();
        List<List<String>> c = cells(imports.sheet(ALL, EPIC));
        c.get(0).set(5, "bình"); // Login's BA, by name, any case
        c.get(1).set(5, "Bình"); // the same on another line of Login
        c.get(2).set(8, "4"); // Fix BAL effort
        c.get(3).set(9, "2026-10-20");
        c.get(3).set(10, "2026-10-21"); // JM Review's dates
        c.get(4).set(5, "Minh"); // yet another BA on a third line of Login (the lines left with An change nothing)
        c.get(5).set(6, "Minh"); // a BAL on a ticket
        c.add(new ArrayList<>(List.of("999", "", "Ghost", "", "", "", "", "", "", "", "")));
        List<ImportLine> lines = imports.preview(ALL, EPIC, c);
        assertThat(lines.get(0).changes()).containsExactly(new ImportChange("ba", "an", "binh"));
        assertThat(lines.get(1).changes()).isEmpty(); // reported once, on the first line naming it
        assertThat(lines.get(2).changes()).containsExactly(new ImportChange("effort_h", "2.5", "4"));
        assertThat(lines.get(3).changes()).extracting(ImportChange::field).containsExactly("start", "end");
        assertThat(lines.get(4).errors()).containsExactly("BA differs from line 2 of Login");
        assertThat(lines.get(5).errors()).containsExactly("A ticket has no BAL: leave BAL empty");
        assertThat(lines.getLast().errors()).singleElement().asString().startsWith("999 is not a sub-task of the plan");
        assertThat(lines.getLast().line()).isEqualTo(c.size() + 1); // the header is line 1
        assertThat(lines.get(0).ref()).isEqualTo(login + ":create");
    }

    @Test
    void peopleAndDatesAreCheckedAsTheScreenOffersThem() {
        List<EstimationRow> rows = planned();
        List<List<String>> c = cells(imports.sheet(ALL, EPIC));
        c.get(0).set(7, "An"); // a BA as JM
        c.get(1).set(9, "2026-10-20"); // a start without an end
        c.get(1).set(10, "");
        c.get(2).set(9, "20/10/2026");
        c.get(3).set(5, "Tanaka"); // a JM as BA
        c.get(4).set(5, "Nobody");
        List<ImportLine> lines = imports.preview(ALL, EPIC, c);
        assertThat(lines.get(0).errors()).containsExactly("An is a BA, not a JM");
        assertThat(lines.get(1).errors()).containsExactly("Set both Plan start and Plan end, or neither");
        assertThat(lines.get(2).errors()).containsExactly("Plan start must be a date (YYYY-MM-DD)");
        assertThat(lines.get(3).errors()).containsExactly("Tanaka is a JM, not a BA");
        assertThat(lines.get(4).errors()).containsExactly("Unknown member Nobody");
        // Pay has 3 steps: no JM Review, so no JM
        long pay = rows.get(2).id();
        List<List<String>> p = cells(imports.sheet(ALL, EPIC)).stream().filter(r -> r.get(0).startsWith(pay + ":")).toList();
        p.get(0).set(7, "Tanaka");
        assertThat(imports.preview(ALL, EPIC, p).get(0).errors()).containsExactly("Pay has no JM Review: leave JM empty");
    }

    @Test
    void applySetsThePeopleEffortsAndDatesByHand() {
        List<EstimationRow> rows = planned();
        long login = rows.get(0).id();
        long run = rows.get(1).id();
        DetailPlanView v = imports.apply(ALL, EPIC, List.of(
                new ImportLineIn(login + ":create", "binh", null, null, 10.0, null, null),
                new ImportLineIn(login + ":fixb", null, null, null, 4.0, null, null),
                new ImportLineIn(String.valueOf(run), null, null, null, null, "2026-10-26", "2026-10-27")));
        EstimationRow l = v.rows().stream().filter(r -> r.id() == login).findFirst().orElseThrow();
        assertThat(List.of(l.baId(), l.balId(), l.jmId())).containsExactly("binh", "minh", "tanaka");
        assertThat(l.effortH()).isEqualTo(10.0); // Create is the row's effort: the other steps follow it
        assertThat(v.byHand()).filteredOn(h -> h.rowId() == login).singleElement().satisfies(h -> assertThat(h.ba()).isTrue());
        assertThat(step(v, login + ":fixb").effortH()).isEqualTo(4.0);
        assertThat(step(v, login + ":fixb").edited()).isTrue();
        StepRow r = step(v, String.valueOf(run));
        assertThat(List.of(r.start(), r.end(), String.valueOf(r.manual()))).containsExactly("2026-10-26", "2026-10-27", "true");
        // the dates stay when the effort is set later, and the other way round
        v = imports.apply(ALL, EPIC, List.of(new ImportLineIn(String.valueOf(run), null, null, null, 12.0, null, null),
                new ImportLineIn(login + ":fixb", null, null, null, null, "2026-10-28", "2026-10-28")));
        assertThat(step(v, String.valueOf(run)).manual()).isTrue();
        assertThat(step(v, login + ":fixb").effortH()).isEqualTo(4.0);
        assertThat(step(v, login + ":fixb").manual()).isTrue();
    }

    @Test
    void applyIsRefusedWhenALineIsWrong() {
        List<EstimationRow> rows = planned();
        long login = rows.get(0).id();
        assertThatThrownBy(() -> imports.apply(ALL, EPIC, List.of(new ImportLineIn(login + ":create", "binh", null, null, null, null, null),
                new ImportLineIn(login + ":balr", "tanaka", null, null, null, null, null))))
                .isInstanceOf(Invalid.class).hasMessageContaining("Tanaka is a JM, not a BA");
        assertThat(estimationRows.findByEpic(EPIC).get(0).baId()).isEqualTo("an"); // nothing applied
    }
}
