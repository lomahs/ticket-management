package tracker.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.EstimationRepository;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;
import tracker.domain.StepWeights;

/** Step 1 of planning: the estimation of an anken, one row per function or ticket, and its effort per phase. */
@Service
public class EstimationService {
    private final TrackerContextLoader context;
    private final EstimationRepository rows;

    public EstimationService(TrackerContextLoader context, EstimationRepository rows) {
        this.context = context;
        this.rows = rows;
    }

    /** @param totalEffortH Σ function totals (weights applied, without JM Review) and the hours of rows without steps */
    public record PhaseEffort(String phase, double totalEffortH) {
    }

    public record EstimationView(List<EstimationRow> rows, List<PhaseEffort> phases) {
    }

    /** {@code epic} must be a tracked anken of the scope. */
    static TrackerContext load(TrackerContextLoader context, AnkenScope scope, String epic) {
        TrackerContext ctx = context.load(scope);
        ctx.anken(epic).orElseThrow(() -> new NotFound("Anken " + epic + " is not tracked", "epic"));
        return ctx;
    }

    public EstimationView view(AnkenScope scope, String epic) {
        TrackerContext ctx = load(context, scope, epic);
        return view(ctx, epic);
    }

    private EstimationView view(TrackerContext ctx, String epic) {
        List<EstimationRow> all = rows.findByEpic(epic);
        return new EstimationView(all, phaseEfforts(all, ctx.settings.stepWeightsOf(epic)).entrySet().stream()
                .map(e -> new PhaseEffort(e.getKey(), e.getValue())).toList());
    }

    /** Phase → total effort, phases in the order of their first row. */
    public static Map<String, Double> phaseEfforts(List<EstimationRow> rows, StepWeights weights) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (EstimationRow r : rows) {
            double h = r.steps() == null ? r.effortH() : weights.functionTotal(r.effortH(), r.steps());
            out.merge(r.phase(), h, Double::sum);
        }
        return out;
    }

    /** Replaces the anken's unpushed rows with {@code next} (in order); a pushed row cannot be changed and stays even when left out. */
    public EstimationView save(AnkenScope scope, String epic, List<EstimationRow> next) {
        TrackerContext ctx = load(context, scope, epic);
        Map<Long, EstimationRow> current = rows.findByEpic(epic).stream().collect(Collectors.toMap(EstimationRow::id, r -> r));
        Set<Long> pushed = current.values().stream().filter(EstimationRow::pushed).map(EstimationRow::id).collect(Collectors.toSet());
        List<EstimationRow> out = new ArrayList<>();
        int position = pushed.size(); // pushed rows keep their place first, the others follow in the order given
        for (int n = 0; n < next.size(); n++) {
            EstimationRow r = next.get(n);
            String at = "rows." + n + ".";
            if (r.id() > 0 && !current.containsKey(r.id())) {
                throw new NotFound("Estimation row " + r.id() + " was not found", at + "id");
            }
            if (pushed.contains(r.id())) {
                EstimationRow was = current.get(r.id());
                if (!sameEstimate(was, r)) {
                    throw new Invalid(was.functionName() + " is already pushed to Jira and cannot be changed", at + "function_name");
                }
                continue;
            }
            check(r, at);
            out.add(new EstimationRow(r.id(), epic, position++, r.phase().strip(), r.subphase().strip(), r.functionName().strip(), r.steps(),
                    r.effortH(), r.baId(), r.balId(), r.jmId(), null));
        }
        rows.save(epic, out);
        return view(ctx, epic);
    }

    private static boolean sameEstimate(EstimationRow a, EstimationRow b) {
        return a.phase().equals(b.phase()) && a.subphase().equals(b.subphase()) && a.functionName().equals(b.functionName())
                && java.util.Objects.equals(a.steps(), b.steps()) && a.effortH() == b.effortH();
    }

    private static void check(EstimationRow r, String at) {
        if (r.phase() == null || r.phase().isBlank()) {
            throw new Invalid("Phase is required", at + "phase");
        }
        if (r.subphase() == null || r.subphase().isBlank()) {
            throw new Invalid("Subphase is required", at + "subphase");
        }
        if (r.functionName() == null || r.functionName().isBlank()) {
            throw new Invalid("Function is required", at + "function_name");
        }
        if (r.steps() != null && r.steps() != 3 && r.steps() != 5) {
            throw new Invalid("Steps must be 3 or 5, or empty for a row without steps", at + "steps");
        }
        if (!(r.effortH() > 0)) {
            throw new Invalid("Effort must be more than 0 hours", at + "effort_h");
        }
    }
}
