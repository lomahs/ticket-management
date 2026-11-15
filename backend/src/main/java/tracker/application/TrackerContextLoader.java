package tracker.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.AnkenRepository;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.OnboardingRepository;
import tracker.application.ports.PlanDayRepository;
import tracker.application.ports.SettingsRepository;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.DraftAnken;
import tracker.domain.Effort;
import tracker.domain.Issue;
import tracker.domain.Leave;
import tracker.domain.Member;
import tracker.domain.Onboarding;
import tracker.domain.TaskNames;
import tracker.domain.Text;
import tracker.domain.WorkCalendar;

/** Loads the team context the screens are computed from, and knows what "today" is for the team. */
@Component
public class TrackerContextLoader {
    private final SettingsRepository settings;
    private final MemberRepository members;
    private final CalendarRepository calendar;
    private final AnkenRepository anken;
    private final IssueRepository issues;
    private final AnkenMemberRepository ankenMembers;
    private final PlanDayRepository planDays;
    private final OnboardingRepository onboarding;
    private final Clock clock;

    public TrackerContextLoader(SettingsRepository settings, MemberRepository members, CalendarRepository calendar,
                                AnkenRepository anken, IssueRepository issues, AnkenMemberRepository ankenMembers, PlanDayRepository planDays,
                                OnboardingRepository onboarding, Clock clock) {
        this.settings = settings;
        this.members = members;
        this.calendar = calendar;
        this.anken = anken;
        this.issues = issues;
        this.ankenMembers = ankenMembers;
        this.planDays = planDays;
        this.onboarding = onboarding;
        this.clock = clock;
    }

    /** YYYY-MM-DD in the team's time zone. */
    public String today() {
        return LocalDate.now(clock).toString();
    }

    public Map<String, Member> membersById() {
        Map<String, Member> out = new LinkedHashMap<>();
        members.findAll().forEach(m -> out.put(m.id(), m));
        return out;
    }

    public WorkCalendar calendar(TrackerSettings s, Map<String, Member> team, List<Leave> leaves) {
        return new WorkCalendar(s.workingHours.days(), calendar.findExtraDays(), calendar.findHolidays(), team, leaves);
    }

    /** The tracked epics in {@code scope}, in tracked order. */
    private static List<String> epicsOf(TrackerSettings s, AnkenScope scope) {
        return s.jira.epics.stream().filter(scope::contains).toList();
    }

    /** The draft anken in {@code scope} ({@link DraftAnken}), by key; only a scope naming a draft has any. */
    private List<Onboarding> draftsOf(AnkenScope scope) {
        if (scope.epics().stream().noneMatch(DraftAnken::isKey)) {
            return List.of();
        }
        return onboarding.findAll().stream().filter(o -> o.draft() && scope.contains(o.epicKey())).sorted(Comparator.comparing(Onboarding::epicKey))
                .toList();
    }

    private static List<String> keysOf(List<String> epics, List<Onboarding> drafts) {
        return Stream.concat(epics.stream(), drafts.stream().map(Onboarding::epicKey)).toList();
    }

    /** Epic to member ids, for the tracked epics in {@code scope}. */
    private Map<String, Set<String>> membersOf(List<String> epics) {
        Map<String, Set<String>> all = ankenMembers.findAll();
        Map<String, Set<String>> out = new LinkedHashMap<>();
        epics.forEach(e -> out.put(e, Set.copyOf(all.getOrDefault(e, Set.of()))));
        return out;
    }

    private static List<String> people(Map<String, Set<String>> membersOf) {
        return membersOf.values().stream().flatMap(Set::stream).distinct().sorted().toList();
    }

    /** The members of the tracked epics and the draft anken in {@code scope}, distinct and sorted by id, without loading any issue. */
    public List<String> people(AnkenScope scope) {
        return people(membersOf(keysOf(epicsOf(settings.load(), scope), draftsOf(scope))));
    }

    /**
     * Members, calendar and the issues of the tracked epics in {@code scope} (JM worklogs dropped), with the flags derived from them. Epics that are not
     * tracked are left out, unless they are draft anken: those come with their template Tasks ({@link DraftAnken#tasks}).
     * {@link TrackerContext#people} are the members of the anken kept.
     */
    public TrackerContext load(AnkenScope scope) {
        TrackerSettings s = settings.load();
        Map<String, Member> team = membersById();
        List<Leave> leaves = calendar.findLeaves();
        List<String> epics = epicsOf(s, scope);
        List<Onboarding> drafts = draftsOf(scope);
        List<Anken> tracking = new ArrayList<>(anken.findAll().stream().filter(a -> epics.contains(a.key())).toList());
        Map<String, Set<String>> membersOf = membersOf(keysOf(epics, drafts));
        List<Issue> loaded = new ArrayList<>(issues.findByEpics(epics).stream().map(i -> Effort.withoutJm(i, team)).toList());
        for (Onboarding d : drafts) {
            tracking.add(new Anken(d.epicKey(), "", d.draftTitle(), ""));
            loaded.addAll(DraftAnken.tasks(d.epicKey(), s.structureOf(d.epicKey()), Text.orElse(s.nameRulesOf(d.epicKey()).taskRule, TaskNames.DEFAULT_RULE),
                    d.ankenPrefix(), d.startedAt().substring(0, 10)));
        }
        TrackerContext ctx = new TrackerContext(today(), s, team, tracking, loaded, leaves,
                calendar(s, team, leaves), people(membersOf), membersOf, planDays.find(loaded.stream().map(Issue::key).toList()));
        ctx.deriveFlags();
        return ctx;
    }
}
