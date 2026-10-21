package tracker.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.SeedRepository;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.JiraReaderFactory;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SecretBox;
import tracker.application.ports.SettingsRepository;
import tracker.application.service.SystemService.Mode;
import tracker.application.settings.TrackerSettings;
import tracker.domain.AccessStatus;
import tracker.domain.Allocation;
import tracker.domain.Issue;
import tracker.domain.IssueKind;
import tracker.domain.Member;
import tracker.domain.Role;
import tracker.domain.Status;
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
    public ApplicationRunner demoAnkenScope(AnkenMemberRepository ankenMembers, SeedRepository seeds, Clock clock, TransactionTemplate tx,
                                            MemberRepository members, IssueRepository issues, AllocationRepository allocations) {
        return args -> tx.executeWithoutResult(status -> {
            String now = Instant.now(clock).toString();
            if (!seeds.done("demo_an_anken")) {
                List<String> keep = List.of("PAY-1100", "PAY-1300");
                ankenMembers.findEpicsOf("an").stream().filter(e -> !keep.contains(e)).forEach(e -> ankenMembers.remove(e, "an"));
                keep.forEach(e -> ankenMembers.add(e, "an", "seed", now));
                seeds.markDone("demo_an_anken", now);
            }
            if (!seeds.done("demo_allocations")) {
                seedAllocations(ankenMembers, members, issues, allocations, now);
                seeds.markDone("demo_allocations", now);
            }
        });
    }

    /**
     * From 2026-09-01: an gives 5h to PAY-1100 and 3h to PAY-1300; every other BA and BAL gives all 8h to the anken they are a
     * member of and have the most open sub-tasks on. Demo data, so it goes straight to the repository, not through the rules.
     */
    private static void seedAllocations(AnkenMemberRepository ankenMembers, MemberRepository members, IssueRepository issues,
                                        AllocationRepository allocations, String now) {
        String from = "2026-09-01";
        allocations.save(new Allocation("an", "PAY-1100", from, 5), "seed", now);
        allocations.save(new Allocation("an", "PAY-1300", from, 3), "seed", now);
        List<Issue> open = issues.findAll().stream()
                .filter(i -> i.kind() == IssueKind.SUBTASK && i.status() != Status.CLOSED && i.assigneeId() != null).toList();
        for (Member m : members.findAll()) {
            if (m.id().equals("an") || (m.role() != Role.BA && m.role() != Role.BAL)) {
                continue;
            }
            Set<String> mine = ankenMembers.findEpicsOf(m.id());
            open.stream().filter(i -> i.assigneeId().equals(m.id()) && mine.contains(i.epicKey()))
                    .collect(Collectors.groupingBy(Issue::epicKey, TreeMap::new, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue())
                    .ifPresent(e -> allocations.save(new Allocation(m.id(), e.getKey(), from, 8), "seed", now));
        }
    }
}
