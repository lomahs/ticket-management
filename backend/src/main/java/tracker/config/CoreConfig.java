package tracker.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import tracker.adapters.crypto.CryptoSecretBox;
import tracker.application.ports.SecretBox;
import tracker.application.service.AccessService;
import tracker.application.service.AnkenMemberSeeder;
import tracker.application.service.AnkenSettingsInitializer;

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

    /** The bootstrap admins from {@code tracker.admins}, lowercased. */
    @Bean
    public AccessService.Config accessConfig(TrackerProperties properties) {
        return new AccessService.Config(properties.admins().stream().map(e -> e.strip().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty()).collect(Collectors.toUnmodifiableSet()));
    }

    @Bean
    public SecretBox secretBox(TrackerProperties properties) {
        return properties.secretKey() != null && !properties.secretKey().isBlank()
                ? new CryptoSecretBox(properties.secretKey())
                : CryptoSecretBox.fromKeyFile(Path.of(properties.dataDir(), "secret.key"));
    }

    /** Gives every tracked epic its own settings, copied from the default; before the memberships are seeded. */
    @Bean
    @Order(-10)
    public ApplicationRunner ankenSettingsInit(AnkenSettingsInitializer initializer) {
        return args -> initializer.ensureAll();
    }

    /** Fills the anken memberships from Jira activity on the first start (in the demo, after the demo data is synced). */
    @Bean
    @Order(0)
    public ApplicationRunner ankenMemberSeed(AnkenMemberSeeder seeder) {
        return args -> seeder.seedOnce();
    }
}
