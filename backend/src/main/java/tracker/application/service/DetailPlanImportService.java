package tracker.application.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Invalid;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.EstimationRepository;
import tracker.application.service.DetailPlanService.DetailPlanView;
import tracker.application.service.DetailPlanService.CellIn;
import tracker.application.service.DetailPlanService.DraftIn;
import tracker.application.service.DetailPlanService.PhaseCheck;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.DetailPlanService.StepIn;
import tracker.application.service.DetailPlanService.MatrixLine;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.Member;
import tracker.domain.Numbers;
import tracker.domain.PlanRows;
import tracker.domain.Role;
import tracker.domain.StepDef;
import tracker.domain.StepEdit;
import tracker.domain.StepWeights;
import tracker.domain.Steps;
import tracker.domain.Text;

/**
 * The detail plan as a sheet to fill offline (Detail plan › Download template) and reading a filled one back (Import): each
 * line is a sub-task of the plan, found by its Ref, with a column per day of the plan's window. A cell left empty in the
 * people and effort columns, or with what the plan has, changes nothing; a day cell left empty is 0 hours.
 */
@Service
public class DetailPlanImportService {
    /**
     * The sheet's columns, in order: the import reads them by position. Ref to Summary are there to read, not to change. One
     * column per day of the plan's window follows, headed YYYY-MM-DD.
     */
    public static final List<String> COLUMNS = List.of("Ref", "Phase", "Function", "Step", "Summary", "BA", "BAL", "JM", "Effort (h)");
    private static final int REF = 0;
    private static final int FUNCTION = 2;
    private static final int BA = 5;
    private static final int BAL = 6;
    private static final int JM = 7;
    private static final int EFFORT = 8;
    private static final int FIRST_DAY = 9;
    private static final double EPS = 1e-6;

    private final TrackerContextLoader context;
    private final EstimationRepository estimation;
    private final DetailPlanService detail;

    public DetailPlanImportService(TrackerContextLoader context, EstimationRepository estimation, DetailPlanService detail) {
        this.context = context;
        this.estimation = estimation;
        this.detail = detail;
    }

    /**
     * One sub-task of the plan in the sheet.
     *
     * @param step the step's label; "Ticket" for a row without steps
     * @param ba   the member's name; BAL and JM are empty where the row has none
     */
    public record SheetLine(String ref, String phase, String function, String step, String summary, String ba, String bal, String jm,
                            double effortH, Map<String, Double> cells) {
    }

    /** A member of the anken the BA / BAL / JM cells may name. */
    public record SheetPerson(String name, String role) {
    }

    /** @param days the day columns, in order */
    public record Sheet(List<SheetLine> lines, List<SheetPerson> people, List<String> days) {
    }

    /**
     * What a line changes.
     *
     * @param field  ba, bal, jm (member ids), effort_h (hours), cells (the days that differ)
     * @param before null: nobody, or not computed
     */
    public record ImportChange(String field, @Nullable String before, @Nullable String after) {
    }

    /**
     * A line of the file, checked against the plan.
     *
     * @param line     the line in the file (the header is line 1)
     * @param function the plan's function of the Ref; the file's when the Ref is not found
     * @param step     the step's value; null for a ticket, and when the Ref is not found
     * @param baId     the member the BA cell names; null when empty
     * @param cells    the hours of each day column of the file, a blank cell as 0
     * @param changes  what applying the line changes; empty when it has errors
     */
    public record ImportLine(int line, String ref, String function, @Nullable String step, @Nullable String baId, @Nullable String balId,
                             @Nullable String jmId, @Nullable Double effortH, Map<String, Double> cells,
                             List<ImportChange> changes, List<String> errors) {
    }

    /** A line to apply, as the preview read it; null keeps what the plan has. @param cells the days of the file; null: none */
    public record ImportLineIn(String ref, @Nullable String baId, @Nullable String balId, @Nullable String jmId, @Nullable Double effortH,
                               @Nullable Map<String, Double> cells) {
    }

    /** A line read from the file or sent back, before it is checked against the plan. */
    private record Parsed(int line, String ref, String function, @Nullable String baId, @Nullable String balId, @Nullable String jmId,
                          @Nullable Double effortH, Map<String, Double> cells, List<String> errors) {
    }

    private enum Slot {
        BA("ba", "BA"), BAL("bal", "BAL"), JM("jm", "JM");

        final String field;
        final String label;

        Slot(String field, String label) {
            this.field = field;
            this.label = label;
        }
    }

    /** The unpushed sub-tasks, removed ones aside, phase by phase as the screen lists them. */
    public Sheet sheet(AnkenScope scope, String epic) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        DetailPlanView v = detail.view(scope, epic);
        Map<Long, EstimationRow> rows = open(v);
        Map<String, List<SheetLine>> byPhase = new LinkedHashMap<>();
        v.checks().stream().map(PhaseCheck::phase).forEach(p -> byPhase.put(p, new ArrayList<>()));
        for (MatrixLine s : v.lines()) {
            EstimationRow r = s.rowId() == null ? null : rows.get(s.rowId());
            if (r == null || s.removed()) {
                continue;
            }
            byPhase.computeIfAbsent(s.phase(), k -> new ArrayList<>()).add(new SheetLine(s.ref(), s.phase(), r.functionName(), label(s.step()),
                    s.summary(), name(ctx, r.baId()), r.steps() != null ? name(ctx, r.balId()) : "", r.steps() != null ? name(ctx, r.jmId()) : "",
                    s.totalH(), hoursByDay(s)));
        }
        List<SheetPerson> people = ctx.ankenMembers.getOrDefault(epic, java.util.Set.of()).stream().map(ctx.members::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Member::name, String.CASE_INSENSITIVE_ORDER))
                .map(m -> new SheetPerson(m.name(), m.role() == null ? "" : m.role().name())).toList();
        return new Sheet(byPhase.values().stream().flatMap(List::stream).toList(), people, v.window());
    }

    /**
     * Checks every line of a filled sheet against the plan; nothing is saved.
     *
     * @param header the file's header row: the columns from the tenth on are days
     * @param rows   the lines, header left out
     */
    public List<ImportLine> preview(AnkenScope scope, String epic, List<String> header, List<List<String>> rows) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        Map<Integer, String> days = new LinkedHashMap<>();
        List<String> headerErrors = new ArrayList<>();
        for (int c = FIRST_DAY; c < header.size(); c++) {
            String text = at(header, c);
            if (text.isEmpty()) {
                continue;
            }
            if (isDate(text)) {
                days.put(c, text);
            } else {
                headerErrors.add("Column " + (c + 1) + ": " + text + " is not a date");
            }
        }
        List<Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            List<String> errors = new ArrayList<>(headerErrors);
            Map<String, Double> cells = new LinkedHashMap<>();
            days.forEach((c, day) -> {
                String text = at(r, c);
                cells.put(day, text.isEmpty() ? Double.valueOf(0) : dayHours(text, day, errors));
            });
            parsed.add(new Parsed(i + 2, at(r, REF), at(r, FUNCTION), member(ctx, at(r, BA), errors), member(ctx, at(r, BAL), errors),
                    member(ctx, at(r, JM), errors), hours(at(r, EFFORT), errors), cells, errors));
        }
        return check(ctx, epic, detail.view(scope, epic), parsed);
    }

    /** Applies the lines of a preview, checked again against the plan as it is now; refused when one has an error. */
    @Transactional
    public DetailPlanView apply(AnkenScope scope, String epic, List<ImportLineIn> in) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        DetailPlanView v = detail.view(scope, epic);
        List<Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            ImportLineIn l = in.get(i);
            List<String> errors = new ArrayList<>();
            Map<String, Double> cells = new LinkedHashMap<>();
            if (l.cells() != null) {
                l.cells().forEach((day, h) -> {
                    if (!isDate(day)) {
                        errors.add(day + " is not a date");
                    } else if (h == null || !(h >= 0)) {
                        errors.add(day + " must be 0 hours or more");
                    } else {
                        cells.put(day, h);
                    }
                });
            }
            parsed.add(new Parsed(i + 1, l.ref().strip(), "", member(ctx, Text.orElse(l.baId(), ""), errors),
                    member(ctx, Text.orElse(l.balId(), ""), errors), member(ctx, Text.orElse(l.jmId(), ""), errors), l.effortH(), cells,
                    errors));
        }
        List<ImportLine> lines = check(ctx, epic, v, parsed);
        for (ImportLine l : lines) {
            if (!l.errors().isEmpty()) {
                throw new Invalid(l.ref() + ": " + String.join(", ", l.errors()), "lines." + (l.line() - 1));
            }
        }
        Map<String, MatrixLine> byRef = byRef(v);
        Map<Long, EstimationRow> rows = open(v);

        // the people: picked by hand, as on the screen
        Map<Long, RowAssign> assigns = new LinkedHashMap<>();
        for (ImportLine l : lines) {
            long rowId = byRef.get(l.ref()).rowId();
            for (ImportChange c : l.changes()) {
                EstimationRow r = rows.get(rowId);
                RowAssign a = assigns.getOrDefault(rowId, new RowAssign(rowId, r.baId(), r.balId(), r.jmId()));
                switch (c.field()) {
                    case "ba" -> assigns.put(rowId, new RowAssign(rowId, c.after(), a.balId(), a.jmId()));
                    case "bal" -> assigns.put(rowId, new RowAssign(rowId, a.baId(), c.after(), a.jmId()));
                    case "jm" -> assigns.put(rowId, new RowAssign(rowId, a.baId(), a.balId(), c.after()));
                    default -> {
                    }
                }
            }
        }
        if (!assigns.isEmpty()) {
            detail.saveDraft(scope, epic, new DraftIn(v.rows().stream().filter(r -> !r.pushed()).map(EstimationRow::id).toList(),
                    List.copyOf(assigns.values())));
        }

        // the effort: of Create and of a ticket it is the row's, the other steps follow it
        for (ImportLine l : lines) {
            if (changed(l, "effort_h")) {
                detail.editStep(scope, epic, new StepIn(byRef.get(l.ref()).rowId(), l.step(), l.effortH(), false));
            }
        }

        // the days: every day of the file for a line whose cells differ, typed
        List<CellIn> typed = new ArrayList<>();
        for (ImportLine l : lines) {
            if (changed(l, "cells")) {
                l.cells().forEach((day, h) -> typed.add(new CellIn(l.ref(), day, h)));
            }
        }
        return typed.isEmpty() ? detail.view(scope, epic) : detail.saveCells(scope, epic, typed);
    }

    private static boolean changed(ImportLine l, String field) {
        return l.changes().stream().anyMatch(c -> c.field().equals(field));
    }

    /**
     * The lines against the plan: each Ref once, a sub-task not in Jira yet and not removed; the people fit the row (as the
     * screen offers them) and agree across the lines of a function; a day's hours differ from the plan's cell.
     */
    private List<ImportLine> check(TrackerContext ctx, String epic, DetailPlanView v, List<Parsed> parsed) {
        Map<String, MatrixLine> byRef = byRef(v);
        Map<Long, EstimationRow> rows = open(v);
        StepWeights w = ctx.settings.stepWeightsOf(epic);
        List<StepEdit> edits = estimation.stepEdits(epic);
        Map<String, Integer> seen = new HashMap<>();
        // row → slot → the first line that names someone new there, and who
        Map<Long, Map<Slot, Map.Entry<Integer, String>>> named = new HashMap<>();
        List<ImportLine> out = new ArrayList<>();
        for (Parsed p : parsed) {
            List<String> errors = new ArrayList<>(p.errors());
            List<ImportChange> changes = new ArrayList<>();
            MatrixLine s = byRef.get(p.ref());
            EstimationRow r = s == null ? null : rows.get(s.rowId());
            if (p.ref().isEmpty()) {
                errors.add("Ref is required: start from the template");
            } else if (s == null || r == null) {
                errors.add(p.ref() + " is not a sub-task of the plan (in Jira already, or from another anken): download the template again");
            } else if (s.removed()) {
                errors.add(s.summary() + " is removed from the plan");
            } else if (seen.containsKey(p.ref())) {
                errors.add("Listed twice: also on line " + seen.get(p.ref()));
            }
            if (s != null && r != null && !s.removed() && !seen.containsKey(p.ref())) {
                seen.put(p.ref(), p.line());
                boolean jmReview = PlanRows.needs(PlanRows.steps(r.id(), r.steps(), r.effortH(), edits, w), Role.JM, w);
                Map<Slot, String> who = new EnumMap<>(Slot.class);
                who.put(Slot.BA, p.baId());
                who.put(Slot.BAL, p.balId());
                who.put(Slot.JM, p.jmId());
                for (Map.Entry<Slot, String> e : who.entrySet()) {
                    String id = e.getValue();
                    if (id == null) {
                        continue;
                    }
                    Slot slot = e.getKey();
                    Member m = ctx.members.get(id);
                    Role role = m == null ? null : m.role();
                    if (m == null || !ctx.isMember(epic, id)) {
                        errors.add(ctx.notAMember(epic, id));
                    } else if (slot == Slot.BAL && r.steps() == null) {
                        errors.add("A ticket has no BAL: leave BAL empty");
                    } else if (slot == Slot.JM && !jmReview) {
                        errors.add(r.functionName() + " has no JM Review: leave JM empty");
                    } else if (slot != Slot.JM && role == Role.JM) {
                        errors.add(m.name() + " is a JM, not a " + (r.steps() == null ? "member of a ticket" : slot.label));
                    } else if (slot == Slot.JM && (role == Role.BA || role == Role.BAL)) {
                        errors.add(m.name() + " is a " + role.name() + ", not a JM");
                    } else {
                        // a line that still has the plan's person changes nothing: the others of the function may change it
                        String now = slot == Slot.BA ? r.baId() : slot == Slot.BAL ? r.balId() : r.jmId();
                        Map.Entry<Integer, String> first = named.computeIfAbsent(r.id(), k -> new EnumMap<>(Slot.class)).get(slot);
                        if (id.equals(now)) {
                            continue;
                        }
                        if (first == null) {
                            named.get(r.id()).put(slot, Map.entry(p.line(), id));
                            changes.add(new ImportChange(slot.field, now, id));
                        } else if (!first.getValue().equals(id)) {
                            errors.add(slot.label + " differs from line " + first.getKey() + " of " + r.functionName());
                        }
                    }
                }
                Double h = p.effortH();
                if (h != null && Math.abs(h - s.totalH()) > EPS) {
                    changes.add(new ImportChange("effort_h", Numbers.format(s.totalH()), Numbers.format(h)));
                }
                Map<String, Double> now = hoursByDay(s);
                List<String> differ = p.cells().entrySet().stream()
                        .filter(e -> Math.abs(e.getValue() - now.getOrDefault(e.getKey(), 0.0)) > EPS).map(Map.Entry::getKey).toList();
                if (!differ.isEmpty()) {
                    changes.add(new ImportChange("cells", differ.stream().map(d -> d + " " + Numbers.format(now.getOrDefault(d, 0.0)))
                            .collect(Collectors.joining(", ")), differ.stream().map(d -> d + " " + Numbers.format(p.cells().get(d)))
                            .collect(Collectors.joining(", "))));
                }
            }
            out.add(new ImportLine(p.line(), p.ref(), r != null ? r.functionName() : p.function(), s != null ? s.step() : null, p.baId(),
                    p.balId(), p.jmId(), p.effortH(), p.cells(), errors.isEmpty() ? changes : List.of(), errors));
        }
        return out;
    }

    private static Map<String, MatrixLine> byRef(DetailPlanView v) {
        Map<String, MatrixLine> out = new HashMap<>();
        v.lines().stream().filter(s -> s.rowId() != null).forEach(s -> out.put(s.ref(), s));
        return out;
    }

    private static Map<Long, EstimationRow> open(DetailPlanView v) {
        Map<Long, EstimationRow> out = new HashMap<>();
        v.rows().stream().filter(r -> !r.pushed()).forEach(r -> out.put(r.id(), r));
        return out;
    }

    private static String label(@Nullable String step) {
        return step == null ? "Ticket" : Steps.STEPS.stream().filter(d -> d.kind().value().equals(step)).map(StepDef::label).findFirst().orElse(step);
    }

    private static String name(TrackerContext ctx, @Nullable String id) {
        if (!Text.hasText(id)) {
            return "";
        }
        Member m = ctx.members.get(id);
        return m == null ? id : m.name();
    }

    /** The member a cell names: by id, name or full name, ignoring case; null when empty. */
    private static @Nullable String member(TrackerContext ctx, String text, List<String> errors) {
        if (text.isEmpty()) {
            return null;
        }
        if (ctx.members.containsKey(text)) {
            return text;
        }
        String t = text.toLowerCase(Locale.ROOT);
        List<Member> found = ctx.members.values().stream().filter(m -> m.id().toLowerCase(Locale.ROOT).equals(t)
                || m.name().toLowerCase(Locale.ROOT).equals(t) || (m.fullName() != null && m.fullName().toLowerCase(Locale.ROOT).equals(t))).toList();
        if (found.size() == 1) {
            return found.getFirst().id();
        }
        errors.add(found.isEmpty() ? "Unknown member " + text : text + " names several members: use their id");
        return null;
    }

    private static @Nullable Double hours(String text, List<String> errors) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            double h = Double.parseDouble(text.replace(',', '.'));
            if (h >= 0) {
                return h;
            }
            errors.add("Effort must be 0 hours or more");
        } catch (NumberFormatException e) {
            errors.add("Effort must be a number of hours");
        }
        return null;
    }

    private static boolean isDate(String text) {
        try {
            return text.matches("\\d{4}-\\d{2}-\\d{2}") && LocalDate.parse(text).toString().equals(text);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Double dayHours(String text, String day, List<String> errors) {
        try {
            double h = Double.parseDouble(text.replace(',', '.'));
            if (h >= 0) {
                return h;
            }
            errors.add(day + " must be 0 hours or more");
        } catch (NumberFormatException e) {
            errors.add(day + " must be a number of hours");
        }
        return 0.0;
    }

    private static Map<String, Double> hoursByDay(MatrixLine s) {
        Map<String, Double> out = new LinkedHashMap<>();
        s.cells().forEach(d -> out.put(d.day(), d.hours()));
        return out;
    }

    private static String at(List<String> r, int i) {
        return i < r.size() ? r.get(i).strip() : "";
    }
}
