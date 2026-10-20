package tracker.adapters.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import tracker.domain.Feature;
import tracker.domain.Level;

/** This API handler needs an active viewer with at least {@code level} on {@code feature} (admins have every level). */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Requires {
    Feature feature();

    Level level();
}
