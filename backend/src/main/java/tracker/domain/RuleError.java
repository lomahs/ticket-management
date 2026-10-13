package tracker.domain;

public class RuleError extends IllegalArgumentException {
    public RuleError(String message) {
        super(message);
    }
}
