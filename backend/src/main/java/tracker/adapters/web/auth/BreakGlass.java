package tracker.adapters.web.auth;

import org.jspecify.annotations.Nullable;

/** The break-glass login (user {@code breakglass}): its bcrypt password hash, or null when it is off. */
public record BreakGlass(@Nullable String passwordHash) {
    public static final String USERNAME = "breakglass";

    public boolean enabled() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
