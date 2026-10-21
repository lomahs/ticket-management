package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.AllocationRepository.StampedAllocation;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.MemberRepository;
import tracker.application.settings.SettingsStore;
import tracker.domain.Allocation;
import tracker.domain.Allocations;
import tracker.domain.AnkenScope;
import tracker.domain.Member;
import tracker.domain.Viewer;

/** Hours per day each member gives each anken, from an effective date. */
@Service
public class AllocationService {
    private final ScopeService scopes;
    private final TrackerContextLoader loader;
    private final AllocationRepository allocations;
    private final AnkenMemberRepository ankenMembers;
    private final MemberRepository members;
    private final TeamSettingsService team;
    private final SettingsStore settings;
    private final Clock clock;

    public AllocationService(ScopeService scopes, TrackerContextLoader loader, AllocationRepository allocations,
                             AnkenMemberRepository ankenMembers, MemberRepository members, TeamSettingsService team,
                             SettingsStore settings, Clock clock) {
        this.scopes = scopes;
        this.loader = loader;
        this.allocations = allocations;
        this.ankenMembers = ankenMembers;
        this.members = members;
        this.team = team;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The allocations that count, and the anken of a scope they count on (its tracked epics).
     *
     * @param allocations only the rows on a tracked epic the member belongs to now
     */
    public record Counted(Allocations allocations, Set<String> epics) {
    }

    /** What Timesheet and Capacity measure {@code scope} against. */
    public Counted counted(AnkenScope scope) {
        Set<String> tracked = Set.copyOf(settings.load().jira.epics);
        Set<String> epics = new HashSet<>(tracked);
        epics.retainAll(scope.epics());
        return new Counted(new Allocations(counting(allocations.findAll(), tracked)), epics);
    }

    /**
     * Only rows on a tracked epic the member currently belongs to count. The others (an anken the member left, an epic no
     * longer tracked) are kept as history and count again when the member is back.
     */
    private List<Allocation> counting(List<Allocation> rows, Set<String> tracked) {
        Map<String, Set<String>> byEpic = ankenMembers.findAll();
        return rows.stream().filter(a -> tracked.contains(a.epicKey())
                && byEpic.getOrDefault(a.epicKey(), Set.of()).contains(a.memberId())).toList();
    }

    /** @param hours the effective hours of every epic in view as of {@code from}, carried forward */
    public record AllocationStep(String from, Map<String, Double> hours, String setBy, String setAt) {
    }

    public record AllocationChange(String from, Map<String, Double> hours) {
    }

    /** @param epics the member's anken that the viewer sees; {@code hoursPerDay} is the working day */
    public record AllocationTimeline(String memberId, List<String> epics, List<AllocationStep> steps, double hoursPerDay) {
    }

    private Member requireVisibleMember(Viewer v, String memberId) {
        if (!loader.load(scopes.scope(v, null)).people.contains(memberId)) {
            throw new NotFound("Member " + memberId + " was not found", "member_id");
        }
        return members.findById(memberId).orElseThrow(() -> new NotFound("Member " + memberId + " was not found", "member_id"));
    }

    private List<String> epicsInView(Viewer v, String memberId) {
        Set<String> mine = ankenMembers.findEpicsOf(memberId);
        return scopes.visible(v).stream().filter(mine::contains).toList();
    }

    public AllocationTimeline timeline(Viewer v, String memberId) {
        requireVisibleMember(v, memberId);
        return build(v, memberId);
    }

    private AllocationTimeline build(Viewer v, String memberId) {
        List<String> epics = epicsInView(v, memberId);
        List<StampedAllocation> rows = allocations.findStampedByMember(memberId).stream()
                .filter(r -> epics.contains(r.allocation().epicKey())).toList();
        Allocations all = new Allocations(rows.stream().map(StampedAllocation::allocation).toList());
        List<AllocationStep> steps = new ArrayList<>();
        for (String date : all.datesOf(memberId)) {
            Map<String, Double> hours = new LinkedHashMap<>();
            for (String epic : epics) {
                Double h = all.hoursOn(memberId, epic, date);
                if (h != null) {
                    hours.put(epic, h);
                }
            }
            StampedAllocation latest = rows.stream().filter(r -> r.allocation().effectiveFrom().equals(date))
                    .max(Comparator.comparing(StampedAllocation::setAt)).orElseThrow();
            steps.add(new AllocationStep(date, hours, latest.setBy(), latest.setAt()));
        }
        return new AllocationTimeline(memberId, epics, steps, team.workingHours().hoursPerDay());
    }

    /** Writes one row per epic in {@code c.hours()} from {@code c.from()}; nothing is saved when any check fails. */
    @Transactional
    public AllocationTimeline set(Viewer v, String memberId, AllocationChange c) {
        Member m = requireVisibleMember(v, memberId);
        Set<String> visible = scopes.visible(v);
        for (Map.Entry<String, Double> e : c.hours().entrySet()) {
            String epic = e.getKey();
            if (!visible.contains(epic) || !ankenMembers.exists(epic, memberId)) {
                throw new Invalid(m.name() + " is not a member of " + epic, "hours." + epic);
            }
            double h = e.getValue();
            if (!(h >= 0 && h <= 24)) {
                throw new Invalid("Hours must be between 0 and 24", "hours." + epic);
            }
        }
        List<Allocation> next = new ArrayList<>(allocations.findByMember(memberId));
        for (Map.Entry<String, Double> e : c.hours().entrySet()) {
            next.removeIf(a -> a.epicKey().equals(e.getKey()) && a.effectiveFrom().equals(c.from()));
            next.add(new Allocation(memberId, e.getKey(), c.from(), e.getValue()));
        }
        List<Allocation> counted = counting(next, Set.copyOf(settings.load().jira.epics));
        Allocations after = new Allocations(counted);
        double day = team.workingHours().hoursPerDay();
        TreeSet<String> dates = new TreeSet<>(after.datesOf(memberId).stream().filter(d -> d.compareTo(c.from()) >= 0).toList());
        dates.add(c.from());
        for (String date : dates) {
            double total = after.totalOn(memberId, date);
            if (total > day) {
                throw new Invalid("Total " + total + "h is more than " + day + "h a day", "hours");
            }
        }
        String now = Instant.now(clock).toString();
        for (Map.Entry<String, Double> e : c.hours().entrySet()) {
            allocations.save(new Allocation(memberId, e.getKey(), c.from(), e.getValue()), v.email(), now);
        }
        return build(v, memberId);
    }
}
