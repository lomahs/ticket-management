package tracker.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Per step: the share of the Create estimate it takes, who Jira assigns it to at push (PIC) and how its planned hours are
 * shared between the function's BA and BAL (effort). An admin may change them, per anken or for all.
 *
 * @param pic    step → the role the sub-task is assigned to: BA or BAL; a missing step takes {@link #DEFAULT_PIC}
 * @param shares step → percent of its hours for the BA and the BAL; a missing step takes {@link #DEFAULT_SHARES}
 */
public record StepWeights(Map<StepKind, Double> weights, Map<StepKind, Role> pic, Map<StepKind, Share> shares) {

    /** Percent of a step's hours the function's BA and BAL carry; 0 and 0: the step counts for nobody (JM Review). */
    public record Share(double ba, double bal) {
        public boolean counted() {
            return ba > 0 || bal > 0;
        }
    }

    public static final Map<StepKind, Role> DEFAULT_PIC = Map.of(StepKind.CREATE, Role.BA, StepKind.BAL_REVIEW, Role.BA,
            StepKind.FIX_BAL, Role.BAL, StepKind.JM_REVIEW, Role.BAL, StepKind.FIX_JM, Role.BAL);
    public static final Map<StepKind, Share> DEFAULT_SHARES = Map.of(StepKind.CREATE, new Share(100, 0),
            StepKind.BAL_REVIEW, new Share(0, 100), StepKind.FIX_BAL, new Share(70, 30), StepKind.JM_REVIEW, new Share(0, 0),
            StepKind.FIX_JM, new Share(70, 30));

    public StepWeights(Map<StepKind, Double> weights, @Nullable Map<StepKind, Role> pic, @Nullable Map<StepKind, Share> shares) {
        this.weights = Map.copyOf(weights);
        this.pic = filled(pic, DEFAULT_PIC);
        this.shares = filled(shares, DEFAULT_SHARES);
    }

    public StepWeights(Map<StepKind, Double> weights) {
        this(weights, null, null);
    }

    private static <V> Map<StepKind, V> filled(@Nullable Map<StepKind, V> given, Map<StepKind, V> defaults) {
        Map<StepKind, V> out = new EnumMap<>(defaults);
        if (given != null) {
            out.putAll(given);
        }
        return Map.copyOf(out);
    }

    /** The weights of {@link Steps#STEPS}, the default PIC and shares. */
    public static StepWeights defaults() {
        Map<StepKind, Double> w = new EnumMap<>(StepKind.class);
        Steps.STEPS.forEach(s -> w.put(s.kind(), s.effortWeight()));
        return new StepWeights(w);
    }

    public double of(StepKind k) {
        return weights.getOrDefault(k, 0.0);
    }

    public Role picOf(StepKind k) {
        return pic.get(k);
    }

    public Share shareOf(StepKind k) {
        return shares.get(k);
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

    /** Hours of a function the BA carries: each step's hours × the step's BA share. */
    public double baTotal(double createH, int steps) {
        return stepsOf(steps).stream().mapToDouble(k -> stepHours(createH, k) * shareOf(k).ba() / 100).sum();
    }
}
