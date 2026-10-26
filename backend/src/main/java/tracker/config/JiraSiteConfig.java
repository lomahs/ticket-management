package tracker.config;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

import tracker.adapters.jira.JiraCloudReader;
import tracker.adapters.jira.JiraCloudWriter;
import tracker.adapters.jira.JiraConfig;
import tracker.application.ports.JiraReaderFactory;
import tracker.application.ports.SecretBox;
import tracker.application.ports.SettingsRepository;
import tracker.application.service.SystemService.Mode;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Text;

/**
 * Without the {@code demo} profile: the team's Jira Cloud site, configured in Settings → Jira. Admins write to it through
 * reviewed changes; the periodic sync runs on the interval set there.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!demo")
@EnableScheduling
public class JiraSiteConfig {

    @Bean
    public Mode mode() {
        return new Mode("jira");
    }

    /** Reads the connection from the settings on every call, so saved changes apply at once. */
    private static JiraConfig connection(SettingsRepository settings, SecretBox secrets) {
        TrackerSettings s = settings.load();
        TrackerSettings.FieldMapping fm = s.fieldMapping;
        List<String> fields = Stream.of(fm.plannedStart, fm.plannedEnd, fm.effort, fm.remaining).filter(Text::hasText).distinct().toList();
        String token = Text.hasText(s.jira.tokenEncrypted) ? secrets.decrypt(s.jira.tokenEncrypted) : "";
        return new JiraConfig(s.jira.url, s.jira.email, token, fields);
    }

    @Bean
    public JiraCloudReader jiraReader(SettingsRepository settings, SecretBox secrets) {
        return new JiraCloudReader(() -> connection(settings, secrets));
    }

    @Bean
    public JiraCloudWriter jiraWriter(SettingsRepository settings, SecretBox secrets) {
        return new JiraCloudWriter(() -> connection(settings, secrets));
    }

    @Bean
    public JiraReaderFactory jiraReaderFactory() {
        return (url, email, token) -> new JiraCloudReader(() -> new JiraConfig(url, email, token, List.of()));
    }
}
