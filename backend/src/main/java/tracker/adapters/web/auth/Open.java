package tracker.adapters.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Who may call this API handler, when it is not about one feature (then it says {@link Requires}). Every handler under
 * {@code /api} has exactly one of the two; {@link AccessInterceptor} enforces it and refuses a handler that has neither.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Open {
    Rule value();

    enum Rule {
        /** No session needed. */
        PUBLIC,
        /** A session, whatever the access. */
        SIGNED_IN,
        /** An active viewer. */
        ACTIVE,
        /** An active admin. */
        ADMIN
    }
}
