package tracker.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tracker.application.ports.AnkenRepository;
import tracker.application.ports.CalendarRepository;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.ports.SettingsRepository;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.Leave;
import tracker.domain.Member;
import tracker.domain.WorkCalendar;

/** Loads the team context the screens are computed from, and knows what "today" is for the team. */
@Component
public class TrackerContextLoader {
    private final SettingsRepository settings;
    private final MemberRepository members;
    private final CalendarRepository calendar;
    private final AnkenRepository anken;
    private final IssueRepository issues;
    private final Clock clock;

    public TrackerContextLoader(SettingsRepository settings, MemberRepository members, CalendarRepository calendar,
                                AnkenRepository anken, IssueRepository issues, Clock clock) {
        this.settings = settings;
        this.members = members;
        this.calendar = calendar;
        this.anken = anken;
        this.issues = issues;
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

    /** Members, calendar and the issues of the tracked epics, with the flags derived from them. */
    public TrackerContext load() {
        TrackerSettings s = settings.load();
        Map<String, Member> team = membersById();
        List<Leave> leaves = calendar.findLeaves();
        List<String> tracked = s.jira.epics;
        List<Anken> tracking = anken.findAll().stream().filter(a -> tracked.contains(a.key())).toList();
        TrackerContext ctx = new TrackerContext(today(), s, team, tracking, issues.findByEpics(tracked), leaves,
                calendar(s, team, leaves));
        ctx.deriveFlags();
        return ctx;
    }
}
