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
import tracker.application.service.DetailPlanService.MatrixLine;
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
        assertThat(detail.view(ALL, EPIC).problems()).isEmpty(); // every slot it needs is filled; no cell is planned yet
        return rows;
    }

    /** The sheet's header, the way the file is read back. */
    static List<String> header(Sheet s) {
        List<String> out = new ArrayList<>(DetailPlanImportService.COLUMNS);
        out.addAll(s.days());
        return out;
    }

    /** The sheet's lines as cells, the way the file is read back (header left out); a day without hours is blank. */
    static List<List<String>> cells(Sheet s) {
        List<List<String>> out = new ArrayList<>();
        for (SheetLine l : s.lines()) {
            List<String> r = new ArrayList<>(List.of(l.ref(), l.phase(), l.function(), l.step(), l.summary(), l.ba(), l.bal(), l.jm(),
                    tracker.domain.Numbers.format(l.effortH())));
            for (String d : s.days()) {
                Double h = l.cells().get(d);
                r.add(h == null || h == 0 ? "" : tracker.domain.Numbers.format(h));
            }
            out.add(r);
        }
        return out;
    }

    List<ImportLine> preview(List<List<String>> rows) {
        return imports.preview(ALL, EPIC, header(imports.sheet(ALL, EPIC)), rows);
    }

    static MatrixLine step(DetailPlanView v, String ref) {
        return v.lines().stream().filter(s -> s.ref().equals(ref)).findFirst().orElseThrow();
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
        assertThat(s.days()).isEqualTo(detail.view(ALL, EPIC).window()).contains("2026-10-12");
        assertThat(DetailPlanImportService.COLUMNS).containsExactly("Ref", "Phase", "Function", "Step", "Summary", "BA", "BAL", "JM", "Effort (h)");
        SheetLine run = s.lines().get(5);
        assertThat(List.of(run.step(), run.ba(), run.bal(), run.jm())).containsExactly("Ticket", "An", "", "");
        assertThat(s.people()).extracting(DetailPlanImportService.SheetPerson::name).contains("An", "Bình", "Minh", "Tanaka");
    }

    @Test
    void theSheetAsItIsChangesNothing() {
        planned();
        List<ImportLine> lines = preview(cells(imports.sheet(ALL, EPIC)));
        assertThat(lines).allSatisfy(l -> assertThat(l.errors()).isEmpty());
        assertThat(lines).allSatisfy(l -> assertThat(l.changes()).isEmpty()); // no cell is planned yet
    }

    @Test
    void previewListsWhatChangesAndWhatIsWrong() {
        List<EstimationRow> rows = planned();
        long login = rows.get(0).id();
        List<List<String>> c = cells(imports.sheet(ALL, EPIC));
        c.get(0).set(5, "bình"); // Login's BA, by name, any case
        c.get(1).set(5, "Bình"); // the same on another line of Login
        c.get(2).set(8, "4"); // Fix BAL effort
        c.get(3).set(9, "8"); // JM Review's first day
        c.get(4).set(5, "Minh"); // yet another BA on a third line of Login (the lines left with An change nothing)
        c.get(5).set(6, "Minh"); // a BAL on a ticket
        c.add(new ArrayList<>(List.of("999", "", "Ghost", "", "", "", "", "", "")));
        List<ImportLine> lines = preview(c);
        assertThat(lines.get(0).changes()).containsExactly(new ImportChange("ba", "an", "binh"));
        assertThat(lines.get(1).changes()).isEmpty(); // reported once, on the first line naming it
        assertThat(lines.get(2).changes()).containsExactly(new ImportChange("effort_h", "2.5", "4"));
        assertThat(lines.get(3).changes()).extracting(ImportChange::field).containsExactly("cells");
        assertThat(lines.get(3).cells()).containsEntry(imports.sheet(ALL, EPIC).days().getFirst(), 8.0);
        assertThat(lines.get(4).errors()).containsExactly("BA differs from line 2 of Login");
        assertThat(lines.get(5).errors()).containsExactly("A ticket has no BAL: leave BAL empty");
        assertThat(lines.getLast().errors()).singleElement().asString().startsWith("999 is not a sub-task of the plan");
        assertThat(lines.getLast().line()).isEqualTo(c.size() + 1); // the header is line 1
        assertThat(lines.get(0).ref()).isEqualTo(login + ":create");
    }

    @Test
    void peopleAndHoursAreCheckedAsTheScreenOffersThem() {
        List<EstimationRow> rows = planned();
        List<List<String>> c = cells(imports.sheet(ALL, EPIC));
        c.get(0).set(7, "An"); // a BA as JM
        c.get(1).set(9, "-2"); // negative hours
        c.get(2).set(9, "lots");
        c.get(3).set(5, "Tanaka"); // a JM as BA
        c.get(4).set(5, "Nobody");
        List<ImportLine> lines = preview(c);
        assertThat(lines.get(0).errors()).containsExactly("An is a BA, not a JM");
        String d = imports.sheet(ALL, EPIC).days().getFirst();
        assertThat(lines.get(1).errors()).containsExactly(d + " must be 0 hours or more");
        assertThat(lines.get(2).errors()).containsExactly(d + " must be a number of hours");
        assertThat(lines.get(3).errors()).containsExactly("Tanaka is a JM, not a BA");
        assertThat(lines.get(4).errors()).containsExactly("Unknown member Nobody");
        // Pay has 3 steps: no JM Review, so no JM
        long pay = rows.get(2).id();
        List<List<String>> p = cells(imports.sheet(ALL, EPIC)).stream().filter(r -> r.get(0).startsWith(pay + ":")).toList();
        p.get(0).set(7, "Tanaka");
        assertThat(preview(p).get(0).errors()).containsExactly("Pay has no JM Review: leave JM empty");
    }

    @Test
    void aHeaderThatIsNotADateIsReported() {
        planned();
        Sheet sheet = imports.sheet(ALL, EPIC);
        List<String> h = header(sheet);
        h.add(9, "foo");
        List<List<String>> c = cells(sheet);
        c.forEach(r -> r.add(9, ""));
        List<ImportLine> lines = imports.preview(ALL, EPIC, h, c);
        assertThat(lines).allSatisfy(l -> assertThat(l.errors()).containsExactly("Column 10: foo is not a date"));
    }

    @Test
    void applySetsThePeopleEffortsAndCellsByHand() {
        List<EstimationRow> rows = planned();
        long login = rows.get(0).id();
        long run = rows.get(1).id();
        List<String> days = imports.sheet(ALL, EPIC).days();
        String d12 = "2026-10-12";
        String d13 = "2026-10-13";
        String d14 = "2026-10-14";
        assertThat(days).contains(d12, d13, d14);
        // a cell that was there: the file clears it with a blank
        detail.saveCells(ALL, EPIC, List.of(new DetailPlanService.CellIn(String.valueOf(run), d14, 4)));
        assertThat(step(detail.view(ALL, EPIC), String.valueOf(run)).cells()).extracting(DetailPlanService.DayHours::day).containsExactly(d14);
        Map<String, Double> runCells = new java.util.LinkedHashMap<>();
        runCells.put(d12, 8.0);
        runCells.put(d13, 8.0);
        runCells.put(d14, 0.0);
        DetailPlanView v = imports.apply(ALL, EPIC, List.of(
                new ImportLineIn(login + ":create", "binh", null, null, 10.0, null),
                new ImportLineIn(login + ":fixb", null, null, null, 4.0, null),
                new ImportLineIn(String.valueOf(run), null, null, null, null, runCells)));
        EstimationRow l = v.rows().stream().filter(r -> r.id() == login).findFirst().orElseThrow();
        assertThat(List.of(l.baId(), l.balId(), l.jmId())).containsExactly("binh", "minh", "tanaka");
        assertThat(l.effortH()).isEqualTo(10.0); // Create is the row's effort: the other steps follow it
        assertThat(v.byHand()).filteredOn(h -> h.rowId() == login).singleElement().satisfies(h -> assertThat(h.ba()).isTrue());
        assertThat(step(v, login + ":fixb").totalH()).isEqualTo(4.0);
        assertThat(step(v, login + ":fixb").edited()).isTrue();
        MatrixLine r = step(v, String.valueOf(run));
        assertThat(r.cells()).extracting(DetailPlanService.DayHours::day, DetailPlanService.DayHours::hours)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(d12, 8.0), org.assertj.core.groups.Tuple.tuple(d13, 8.0));
        // the cells stay when the effort is set later
        v = imports.apply(ALL, EPIC, List.of(new ImportLineIn(String.valueOf(run), null, null, null, 12.0, null)));
        assertThat(step(v, String.valueOf(run)).cells()).hasSize(2);
        assertThat(step(v, String.valueOf(run)).totalH()).isEqualTo(12.0);
    }

    @Test
    void applyIsRefusedWhenALineIsWrong() {
        List<EstimationRow> rows = planned();
        long login = rows.get(0).id();
        assertThatThrownBy(() -> imports.apply(ALL, EPIC, List.of(new ImportLineIn(login + ":create", "binh", null, null, null, null),
                new ImportLineIn(login + ":balr", "tanaka", null, null, null, null))))
                .isInstanceOf(Invalid.class).hasMessageContaining("Tanaka is a JM, not a BA");
        assertThat(estimationRows.findByEpic(EPIC).get(0).baId()).isEqualTo("an"); // nothing applied
    }
}
