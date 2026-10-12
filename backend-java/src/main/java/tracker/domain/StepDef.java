package tracker.domain;

/**
 * One step of the 5-step function workflow.
 *
 * @param durationDays default plan length when scheduling a new function
 * @param effortWeight share of the Create estimate
 * @param role         who is assigned this step when a function is created
 */
public record StepDef(StepKind kind, String label, int durationDays, boolean review, boolean fix, double effortWeight,
                      Role role) {
}
