package tracker.application;

/** Errors use cases raise; the API maps each to an HTTP status and a problem+json body. */
public class AppError extends RuntimeException {
    private final int status;
    private final String code;
    private final String field;

    protected AppError(int status, String code, String detail, String field) {
        super(detail);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return getMessage();
    }

    /** The request field the error is about, or null. */
    public String field() {
        return field;
    }

    public static class Invalid extends AppError {
        public Invalid(String detail, String field) {
            super(422, "invalid", detail, field);
        }

        public Invalid(String detail) {
            this(detail, null);
        }
    }

    public static class NotFound extends AppError {
        public NotFound(String detail, String field) {
            super(404, "not_found", detail, field);
        }

        public NotFound(String detail) {
            this(detail, null);
        }
    }

    public static class Conflict extends AppError {
        public Conflict(String detail, String field) {
            super(409, "conflict", detail, field);
        }
    }

    public static class CapabilityDisabled extends AppError {
        public CapabilityDisabled(String detail) {
            super(403, "capability_disabled", detail, null);
        }
    }
}
