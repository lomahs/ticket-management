package tracker.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Share of the Create estimate each step takes; an admin may change them, per anken or for all. */
public record StepWeights(Map<StepKind, Double> weights) {

    public StepWeights {
        weights = Map.copyOf(weights);
    }

    /** The weights of {@link Steps#STEPS}. */
    public static StepWeights defaults() {
        Map<StepKind, Double> w = new EnumMap<>(StepKind.class);
        Steps.STEPS.forEach(s -> w.put(s.kind(), s.effortWeight()));
        return new StepWeights(w);
    }

    public double of(StepKind k) {
        return weights.getOrDefault(k, 0.0);
    }

    /** The steps of a 3- or 5-step function, in order. */
    public static List<StepKind> stepsOf(int count) {
        if (count != 3 && count != Steps.COUNT) {
            throw new IllegalArgumentException("A function has 3 or 5 steps, not " + count);
        }
        return Steps.STEPS.subList(0, count).stream().map(StepDef::kind).toList();
    }

    public double stepHours(double createH, StepKind k) {
        return Scheduling.stepEstimate(createH, of(k));
    }

    /** Hours of a function's steps without JM Review, which only sets the dates of its own step. */
    public double functionTotal(double createH, int steps) {
        return stepsOf(steps).stream().filter(k -> k != StepKind.JM_REVIEW).mapToDouble(k -> stepHours(createH, k)).sum();
    }
}
