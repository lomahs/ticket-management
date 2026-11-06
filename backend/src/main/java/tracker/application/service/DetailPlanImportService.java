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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Invalid;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.EstimationRepository;
import tracker.application.service.DetailPlanService.DetailPlanView;
import tracker.application.service.DetailPlanService.DraftIn;
import tracker.application.service.DetailPlanService.PhaseCheck;
import tracker.application.service.DetailPlanService.RowAssign;
import tracker.application.service.DetailPlanService.StepRow;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.Member;
import tracker.domain.Numbers;
import tracker.domain.PlanRows;
import tracker.domain.Role;
import tracker.domain.StepDef;
import tracker.domain.StepEdit;
import tracker.domain.StepKind;
import tracker.domain.StepWeights;
import tracker.domain.Steps;
import tracker.domain.Text;

/**
 * The detail plan as a sheet to fill offline (Detail plan › Download template) and reading a filled one back (Import): each
 * line is a sub-task of the plan, found by its Ref. A cell left empty, or with what the plan has, changes nothing; a value
 * that differs is set by hand, as on the screen.
 */
@Service
public class DetailPlanImportService {
    /** The sheet's columns, in order: the import reads them by position. Ref to Sub-task are there to read, not to change. */
    public static final List<String> COLUMNS = List.of("Ref", "Phase", "Function", "Step", "Sub-task", "BA", "BAL", "JM", "Effort (h)",
            "Plan start", "Plan end");
    private static final int REF = 0;
    private static final int FUNCTION = 2;
    private static final int BA = 5;
    private static final int BAL = 6;
    private static final int JM = 7;
    private static final int EFFORT = 8;
    private static final int START = 9;
    private static final int END = 10;
    private static final double EPS = 1e-6;
    private static final String DATE_ERROR = " must be a date (YYYY-MM-DD)";

    private final TrackerContextLoader context;
    private final EstimationRepository estimation;
    private final DetailPlanService detail;
    private final EstimationService estimations;

    public DetailPlanImportService(TrackerContextLoader context, EstimationRepository estimation, DetailPlanService detail,
                                   EstimationService estimations) {
        this.context = context;
        this.estimation = estimation;
        this.detail = detail;
        this.estimations = estimations;
    }

    /**
     * One sub-task of the plan in the sheet.
     *
     * @param step the step's label; "Ticket" for a row without steps
     * @param ba   the member's name; BAL and JM are empty where the row has none
     */
    public record SheetLine(String ref, String phase, String function, String step, String summary, String ba, String bal, String jm,
                            double effortH, @Nullable String start, @Nullable String end) {
    }

    /** A member of the anken the BA / BAL / JM cells may name. */
    public record SheetPerson(String name, String role) {
    }

    public record Sheet(List<SheetLine> lines, List<SheetPerson> people) {
    }

    /**
     * What a line changes.
     *
     * @param field  ba, bal, jm (member ids), effort_h (hours), start, end (ISO dates)
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
     * @param changes  what applying the line changes; empty when it has errors
     */
    public record ImportLine(int line, String ref, String function, @Nullable String step, @Nullable String baId, @Nullable String balId,
                             @Nullable String jmId, @Nullable Double effortH, @Nullable String start, @Nullable String end,
                             List<ImportChange> changes, List<String> errors) {
    }

    /** A line to apply, as the preview read it; null keeps what the plan has. */
    public record ImportLineIn(String ref, @Nullable String baId, @Nullable String balId, @Nullable String jmId, @Nullable Double effortH,
                               @Nullable String start, @Nullable String end) {
    }

    /** A line read from the file or sent back, before it is checked against the plan. */
    private record Parsed(int line, String ref, String function, @Nullable String baId, @Nullable String balId, @Nullable String jmId,
                          @Nullable Double effortH, @Nullable String start, @Nullable String end, List<String> errors) {
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
        for (StepRow s : v.steps()) {
            EstimationRow r = rows.get(s.rowId());
            if (r == null || s.removed()) {
                continue;
            }
            byPhase.computeIfAbsent(s.phase(), k -> new ArrayList<>()).add(new SheetLine(s.ref(), s.phase(), r.functionName(), label(s.step()),
                    s.summary(), name(ctx, r.baId()), r.steps() != null ? name(ctx, r.balId()) : "", r.steps() != null ? name(ctx, r.jmId()) : "",
                    s.effortH(), s.start(), s.end()));
        }
        List<SheetPerson> people = ctx.ankenMembers.getOrDefault(epic, java.util.Set.of()).stream().map(ctx.members::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Member::name, String.CASE_INSENSITIVE_ORDER))
                .map(m -> new SheetPerson(m.name(), m.role() == null ? "" : m.role().name())).toList();
        return new Sheet(byPhase.values().stream().flatMap(List::stream).toList(), people);
    }

    /** Checks every line of a filled sheet (header skipped) against the plan; nothing is saved. */
    public List<ImportLine> preview(AnkenScope scope, String epic, List<List<String>> rows) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        List<Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            List<String> errors = new ArrayList<>();
            parsed.add(new Parsed(i + 2, at(r, REF), at(r, FUNCTION), member(ctx, at(r, BA), errors), member(ctx, at(r, BAL), errors),
                    member(ctx, at(r, JM), errors), hours(at(r, EFFORT), errors), date(at(r, START), "Plan start", errors),
                    date(at(r, END), "Plan end", errors), errors));
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
            parsed.add(new Parsed(i + 1, l.ref().strip(), "", member(ctx, Text.orElse(l.baId(), ""), errors),
                    member(ctx, Text.orElse(l.balId(), ""), errors), member(ctx, Text.orElse(l.jmId(), ""), errors), l.effortH(),
                    date(Text.orElse(l.start(), ""), "Plan start", errors), date(Text.orElse(l.end(), ""), "Plan end", errors), errors));
        }
        List<ImportLine> lines = check(ctx, epic, v, parsed);
        for (ImportLine l : lines) {
            if (!l.errors().isEmpty()) {
                throw new Invalid(l.ref() + ": " + String.join(", ", l.errors()), "lines." + (l.line() - 1));
            }
        }
        Map<String, StepRow> byRef = byRef(v);
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

        // the effort of Create and of a ticket is the row's: the other steps follow it
        Map<Long, Double> rowEffort = new HashMap<>();
        for (ImportLine l : lines) {
            if (changed(l, "effort_h") && (l.step() == null || l.step().equals(StepKind.CREATE.value()))) {
                rowEffort.put(byRef.get(l.ref()).rowId(), l.effortH());
            }
        }
        if (!rowEffort.isEmpty()) {
            List<EstimationRow> next = estimation.findByEpic(epic).stream().filter(r -> !r.pushed()).map(r -> !rowEffort.containsKey(r.id()) ? r
                    : new EstimationRow(r.id(), epic, r.position(), r.phase(), r.subphase(), r.functionName(), r.steps(), rowEffort.get(r.id()),
                    r.baId(), r.balId(), r.jmId(), r.taskKey(), null)).toList();
            estimations.save(scope, epic, next);
        }

        // the other steps' effort and the dates: kept with what else the step had set by hand
        List<StepEdit> edits = estimation.stepEdits(epic);
        for (ImportLine l : lines) {
            boolean effort = changed(l, "effort_h") && l.step() != null && !l.step().equals(StepKind.CREATE.value());
            boolean dates = changed(l, "start") || changed(l, "end");
            if (!effort && !dates) {
                continue;
            }
            long rowId = byRef.get(l.ref()).rowId();
            StepKind k = l.step() == null ? null : StepKind.of(l.step());
            StepEdit e = edits.stream().filter(x -> x.rowId() == rowId && x.step() == k).findFirst().orElse(null);
            estimation.saveStepEdit(new StepEdit(rowId, k, effort ? l.effortH() : e == null ? null : e.effortH(),
                    dates ? l.start() : e == null ? null : e.start(), dates ? l.end() : e == null ? null : e.end(), e != null && e.removed()));
        }
        return detail.view(scope, epic);
    }

    private static boolean changed(ImportLine l, String field) {
        return l.changes().stream().anyMatch(c -> c.field().equals(field));
    }

    /**
     * The lines against the plan: each Ref once, a sub-task not in Jira yet and not removed; the people fit the row (as the
     * screen offers them) and agree across the lines of a function; dates come in pairs.
     */
    private List<ImportLine> check(TrackerContext ctx, String epic, DetailPlanView v, List<Parsed> parsed) {
        Map<String, StepRow> byRef = byRef(v);
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
            StepRow s = byRef.get(p.ref());
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
                if (h != null && Math.abs(h - s.effortH()) > EPS) {
                    changes.add(new ImportChange("effort_h", Numbers.format(s.effortH()), Numbers.format(h)));
                }
                if (p.errors().stream().anyMatch(e -> e.endsWith(DATE_ERROR))) {
                    // reported as it was read
                } else if ((p.start() == null) != (p.end() == null)) {
                    errors.add("Set both Plan start and Plan end, or neither");
                } else if (p.start() != null && p.end().compareTo(p.start()) < 0) {
                    errors.add("Plan end is before Plan start");
                } else if (p.start() != null && (!p.start().equals(s.start()) || !p.end().equals(s.end()))) {
                    changes.add(new ImportChange("start", s.start(), p.start()));
                    changes.add(new ImportChange("end", s.end(), p.end()));
                }
            }
            out.add(new ImportLine(p.line(), p.ref(), r != null ? r.functionName() : p.function(), s != null ? s.step() : null, p.baId(),
                    p.balId(), p.jmId(), p.effortH(), p.start(), p.end(), errors.isEmpty() ? changes : List.of(), errors));
        }
        return out;
    }

    private static Map<String, StepRow> byRef(DetailPlanView v) {
        Map<String, StepRow> out = new HashMap<>();
        v.steps().forEach(s -> out.put(s.ref(), s));
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

    private static @Nullable String date(String text, String what, List<String> errors) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(text).toString();
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        errors.add(what + DATE_ERROR);
        return null;
    }

    private static String at(List<String> r, int i) {
        return i < r.size() ? r.get(i).strip() : "";
    }
}
