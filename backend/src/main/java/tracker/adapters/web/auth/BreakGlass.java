package tracker.adapters.web.auth;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** The break-glass login (user {@code breakglass}): its bcrypt password hash, or null when it is off. */
public record BreakGlass(@Nullable String passwordHash) {
    public static final String USERNAME = "breakglass";

    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    /**
     * The break-glass login of a configured hash: off when it is unset or blank. A value that is not a bcrypt hash fails
     * (the message never shows the value), so a typo does not leave a login that nobody can use.
     */
    public static BreakGlass of(@Nullable String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            return new BreakGlass(null);
        }
        String hash = passwordHash.strip();
        if (!BCRYPT.matcher(hash).matches()) {
            throw new IllegalStateException("TRACKER_BREAKGLASS_PASSWORD_HASH is not a bcrypt hash (like $2a$10$ followed by"
                    + " 53 characters). Fix it, or unset it to turn the break-glass login off.");
        }
        return new BreakGlass(hash);
    }

    public boolean enabled() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}
