package tracker.adapters.api;

import java.util.List;

/** A request that failed validation (422 "invalid"): every problem found, in the order the fields were checked. */
public final class RequestInvalid extends RuntimeException {
    /** @param field dotted path such as "overrides.0.start", or null for the body as a whole */
    public record FieldError(String field, String message) {
    }

    private final List<FieldError> errors;

    public RequestInvalid(List<FieldError> errors) {
        super(errors.isEmpty() ? "Invalid request" : errors.get(0).message());
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
