package tracker.application;

import org.jspecify.annotations.Nullable;

/** Errors the use cases raise for the caller to see; the web layer maps each kind to a status. */
public abstract class AppError extends RuntimeException {
    private final @Nullable String field;

    protected AppError(String detail, @Nullable String field) {
        super(detail);
        this.field = field;
    }

    /** The request field the error is about, or null. */
    public @Nullable String field() {
        return field;
    }

    /** A value the rules reject. */
    public static class Invalid extends AppError {
        public Invalid(String detail, @Nullable String field) {
            super(detail, field);
        }

        public Invalid(String detail) {
            this(detail, null);
        }
    }

    public static class NotFound extends AppError {
        public NotFound(String detail, @Nullable String field) {
            super(detail, field);
        }

        public NotFound(String detail) {
            this(detail, null);
        }
    }

    public static class Conflict extends AppError {
        public Conflict(String detail, @Nullable String field) {
            super(detail, field);
        }
    }

    /** The configured Jira site cannot be written to (phase 1). */
    public static class CapabilityDisabled extends AppError {
        public CapabilityDisabled(String detail) {
            super(detail, null);
        }
    }
}
