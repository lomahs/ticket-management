package tracker.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tracker.adapters.crypto.CryptoSecretBox;
import tracker.application.ports.SecretBox;

/** Beans every profile needs: the team's clock and the encryption of the stored Jira token. */
@Configuration(proxyBeanMethods = false)
public class CoreConfig {

    /** The system clock in the team's time zone; with {@code tracker.today} the date is pinned and the time keeps moving. */
    @Bean
    public Clock clock(TrackerProperties properties) {
        Clock system = Clock.system(properties.timeZone());
        if (properties.today() == null || properties.today().isBlank()) {
            return system;
        }
        LocalDate pinned = LocalDate.parse(properties.today());
        return Clock.offset(system, Duration.between(LocalDate.now(system).atStartOfDay(), pinned.atStartOfDay()));
    }

    @Bean
    public SecretBox secretBox(TrackerProperties properties) {
        return properties.secretKey() != null && !properties.secretKey().isBlank()
                ? new CryptoSecretBox(properties.secretKey())
                : CryptoSecretBox.fromKeyFile(Path.of(properties.dataDir(), "secret.key"));
    }
}
