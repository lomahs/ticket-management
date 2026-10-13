package tracker.config;

import java.time.ZoneId;

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
 */
@ConfigurationProperties("tracker")
public record TrackerProperties(@Nullable String today, @DefaultValue("Asia/Ho_Chi_Minh") ZoneId timeZone,
                                @Nullable String secretKey, @DefaultValue("data") String dataDir,
                                @DefaultValue("../frontend/dist") String staticDir) {
}
