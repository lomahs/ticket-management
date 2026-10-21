package tracker.config;

import java.time.ZoneId;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the deployment ({@code tracker.*}, or the environment variables {@code TRACKER_*}).
 *
 * @param today     pins "today" (YYYY-MM-DD) while the time of day keeps moving; unset: the real date
 * @param timeZone  the team's time zone, which decides what "today" is
 * @param secretKey key that encrypts the stored Jira token; unset: a key file in {@code dataDir}, created on first run
 * @param dataDir   where the database and the key file live
 * @param staticDir the built frontend to serve, when it exists
 * @param admins    e-mails of the bootstrap admins ({@code TRACKER_ADMINS}, comma-separated): always admin and active
 * @param breakglassPasswordHash bcrypt hash of the break-glass password (user {@code breakglass}); unset: break-glass is off
 * @param ldap      how a login finds its directory entry ({@code tracker.ldap.*}); the connection is {@code spring.ldap.*}
 */
@ConfigurationProperties("tracker")
public record TrackerProperties(@Nullable String today, @DefaultValue("Asia/Ho_Chi_Minh") ZoneId timeZone,
                                @Nullable String secretKey, @DefaultValue("data") String dataDir,
                                @DefaultValue("../frontend/dist") String staticDir,
                                @DefaultValue("") List<String> admins, @Nullable String breakglassPasswordHash,
                                @DefaultValue Ldap ldap) {

    /**
     * @param userFilter    LDAP search filter for the username typed at login ({@code {0}}), under {@code spring.ldap.base}
     * @param mailAttribute the attribute with the e-mail: the identity of the user, matched to the member's e-mail
     * @param nameAttribute the attribute with the display name
     * @param adDomain      set: Active Directory without a service account, a bind as {@code username@adDomain} that
     *                      reads the user's own entry; {@code userFilter} and {@code spring.ldap.username} are not used
     */
    public record Ldap(@DefaultValue("(|(uid={0})(sAMAccountName={0})(mail={0}))") String userFilter,
                       @DefaultValue("mail") String mailAttribute, @DefaultValue("displayName") String nameAttribute,
                       @Nullable String adDomain) {
        public Ldap {
            if (adDomain != null) {
                adDomain = adDomain.strip().replaceFirst("^@", "");
                adDomain = adDomain.isEmpty() ? null : adDomain;
            }
        }
    }
}
