package tracker.config;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

import tracker.adapters.fake.Dataset;
import tracker.adapters.fake.FakeJira;
import tracker.application.SyncService;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.JiraReaderFactory;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SecretBox;
import tracker.application.ports.SettingsRepository;
import tracker.application.service.SystemService.Mode;
import tracker.application.settings.TrackerSettings;
import tracker.domain.WorkStructure;

/**
 * The {@code demo} profile: a simulated Jira site (writes enabled) and demo data. Every start begins with a new database
 * ({@code tracker.reset-database}), seeds the team, calendar and settings, and runs a full sync, before the server accepts
 * requests.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoConfig {

    @Bean
    public Mode mode() {
        return new Mode("demo");
    }

    @Bean
    public FakeJira fakeJira(Clock clock) {
        return new FakeJira(clock);
    }

    @Bean
    public JiraReaderFactory jiraReaderFactory(FakeJira jira) {
        return (url, email, token) -> jira;
    }

    @Bean
    public SmartInitializingSingleton demoData(MemberRepository members, CalendarRepository calendar, SettingsRepository settings,
                                               SecretBox secrets, SyncService sync, TransactionTemplate tx) {
        return () -> {
            tx.executeWithoutResult(status -> {
                Dataset.demoMembers().forEach(members::save);
                Dataset.demoHolidays().values().forEach(calendar::saveHoliday);
                Dataset.demoLeaves().forEach(calendar::saveLeave);
                Dataset.EXTRA_DAYS.forEach(calendar::saveExtraDay);
                TrackerSettings s = new TrackerSettings();
                s.jira = new TrackerSettings.JiraConnection("https://acme-pay.atlassian.net", "tracker-bot@acme.vn",
                        secrets.encrypt("ATATT3xFfGF0-demo-token"), "15", Dataset.TRACKED_EPICS, "ok");
                s.statusMapping = new TrackerSettings.StatusMapping(Dataset.STATUS_BY_NAME);
                s.structure = new WorkStructure(List.of("Prepare", "Implement_Coding", "Function Unit Test"), List.of());
                settings.save(s);
            });
            sync.run(true);
        };
    }
}
