package tracker.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionTemplate;

import tracker.adapters.fake.Dataset;
import tracker.adapters.fake.FakeJira;
import tracker.adapters.web.auth.DemoLogins;
import tracker.application.SyncService;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.SeedRepository;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.JiraReaderFactory;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SecretBox;
import tracker.application.ports.SettingsRepository;
import tracker.application.service.SystemService.Mode;
import tracker.application.settings.TrackerSettings;
import tracker.domain.AccessStatus;
import tracker.domain.WorkStructure;

/**
 * The {@code demo} profile: a simulated Jira site (writes enabled) and demo data. Every start begins with a new database
 * ({@code tracker.reset-database}), seeds the team (an, binh, chi, dung and minh can sign in), calendar and settings, and
 * runs a full sync, before the server accepts requests. Sign-in goes through an embedded LDAP server (demo-users.ldif).
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

    /** The users of the embedded LDAP server (demo-users.ldif) for the login page; all have the password "demo". */
    @Bean
    public DemoLogins demoLogins() {
        return new DemoLogins(List.of("minh (admin)", "an", "binh", "chi", "dung", "lan (no access yet)"), "demo");
    }

    /** The demo members that can sign in; tanaka (JM) cannot, and lan (in the directory only) has to request access. */
    static final List<String> ACTIVE_MEMBERS = List.of("an", "binh", "chi", "dung", "minh");

    @Bean
    public SmartInitializingSingleton demoData(MemberRepository members, CalendarRepository calendar, SettingsRepository settings,
                                               AccessRepository access, SecretBox secrets, SyncService sync, TransactionTemplate tx) {
        return () -> {
            tx.executeWithoutResult(status -> {
                Dataset.demoMembers().forEach(members::save);
                ACTIVE_MEMBERS.forEach(id -> access.saveStatus(id, AccessStatus.ACTIVE));
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

    /**
     * Every demo member works on PAY-1100, so the seeded memberships would not show any separation: after the seed, an
     * belongs to PAY-1100 and PAY-1300 only. Runs once, like the seed itself.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public ApplicationRunner demoAnkenScope(AnkenMemberRepository ankenMembers, SeedRepository seeds, Clock clock, TransactionTemplate tx) {
        return args -> tx.executeWithoutResult(status -> {
            if (seeds.done("demo_an_anken")) {
                return;
            }
            String now = Instant.now(clock).toString();
            List<String> keep = List.of("PAY-1100", "PAY-1300");
            ankenMembers.findEpicsOf("an").stream().filter(e -> !keep.contains(e)).forEach(e -> ankenMembers.remove(e, "an"));
            keep.forEach(e -> ankenMembers.add(e, "an", "seed", now));
            seeds.markDone("demo_an_anken", now);
        });
    }
}
