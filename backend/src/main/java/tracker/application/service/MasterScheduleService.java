package tracker.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.Invalid;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.MasterScheduleRepository;
import tracker.domain.Allocations;
import tracker.domain.AnkenScope;
import tracker.domain.Dates;
import tracker.domain.MasterSchedule;
import tracker.domain.RuleError;
import tracker.domain.ScheduleVersion;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.Viewer;

/** Step 2 of planning: start and members of each phase, its end computed from the estimation and the allocations; saved as versions. */
@Service
public class MasterScheduleService {
    private final TrackerContextLoader context;
    private final EstimationRepository estimation;
    private final MasterScheduleRepository versions;
    private final AllocationService allocations;
    private final Clock clock;

    public MasterScheduleService(TrackerContextLoader context, EstimationRepository estimation, MasterScheduleRepository versions,
                                 AllocationService allocations, Clock clock) {
        this.context = context;
        this.estimation = estimation;
        this.versions = versions;
        this.allocations = allocations;
        this.clock = clock;
    }

    /** @param start null: the working day after the previous phase's End (not for the first phase) */
    public record PhaseRowIn(String phase, @Nullable String start, List<String> memberIds) {
    }

    /** A phase of the current version against the baseline; days are calendar days, positive = later. */
    public record PhaseShift(String phase, @Nullable String baselineStart, @Nullable String baselineEnd, String start, String end,
                             int startDays, int endDays) {
    }

    /** @param current the latest version, the baseline being the first of {@code versions} */
    public record MasterScheduleView(List<String> estimationPhases, @Nullable ScheduleVersion current, List<ScheduleVersion> versions,
                                     List<PhaseShift> shifts) {
    }

    public MasterScheduleView view(AnkenScope scope, String epic) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        return view(ctx, epic);
    }

    private MasterScheduleView view(TrackerContext ctx, String epic) {
        List<ScheduleVersion> all = versions.findByEpic(epic);
        ScheduleVersion current = all.isEmpty() ? null : all.getLast();
        List<PhaseShift> shifts = new ArrayList<>();
        if (current != null) {
            ScheduleVersion base = all.getFirst();
            for (PhaseRow p : current.phases()) {
                PhaseRow b = base.phases().stream().filter(x -> x.phase().equals(p.phase())).findFirst().orElse(null);
                shifts.add(b == null ? new PhaseShift(p.phase(), null, null, p.start(), p.end(), 0, 0)
                        : new PhaseShift(p.phase(), b.start(), b.end(), p.start(), p.end(), Dates.dayDiff(b.start(), p.start()),
                        Dates.dayDiff(b.end(), p.end())));
            }
        }
        return new MasterScheduleView(ctx.settings.structureOf(epic).orderedPhases(efforts(ctx, epic).keySet()), current, all, shifts);
    }

    private Map<String, Double> efforts(TrackerContext ctx, String epic) {
        return EstimationService.phaseEfforts(estimation.findByEpic(epic), ctx.settings.stepWeightsOf(epic));
    }

    /** The rows as they would be saved; nothing is stored. */
    public List<PhaseRow> preview(AnkenScope scope, String epic, List<PhaseRowIn> phases) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        return compute(ctx, scope, epic, phases);
    }

    private List<PhaseRow> compute(TrackerContext ctx, AnkenScope scope, String epic, List<PhaseRowIn> phases) {
        Map<String, Double> efforts = efforts(ctx, epic);
        Allocations a = allocations.counted(scope).allocations();
        Set<String> seen = new HashSet<>();
        List<PhaseRow> out = new ArrayList<>();
        for (int n = 0; n < phases.size(); n++) {
            PhaseRowIn in = phases.get(n);
            String at = "phases." + n + ".";
            if (!efforts.containsKey(in.phase())) {
                throw new Invalid(in.phase() + " is not a phase of the estimation", at + "phase");
            }
            if (!seen.add(in.phase())) {
                throw new Invalid(in.phase() + " is listed twice", at + "phase");
            }
            if (in.memberIds().isEmpty()) {
                throw new Invalid("Pick the members of " + in.phase(), at + "member_ids");
            }
            for (String m : in.memberIds()) {
                if (!ctx.isMember(epic, m)) {
                    throw new Invalid(ctx.notAMember(epic, m), at + "member_ids");
                }
            }
            String start = in.start();
            if (start == null || start.isBlank()) {
                if (out.isEmpty()) {
                    throw new Invalid("Enter the Start of " + in.phase(), at + "start");
                }
                start = ctx.cal.nextWork(Dates.addDays(out.getLast().end(), 1), in.memberIds().getFirst());
            }
            double effort = efforts.get(in.phase());
            try {
                MasterSchedule.PhaseEnd end = MasterSchedule.endOf(epic, start, effort, in.memberIds(), a, ctx.cal);
                out.add(new PhaseRow(in.phase(), effort, in.memberIds(), start, end.end(), end.planMd()));
            } catch (RuleError e) {
                throw new Invalid(e.getMessage(), at + "member_ids");
            }
        }
        return out;
    }

    /** Saves a new version; the first one of the anken is its baseline. */
    public MasterScheduleView save(Viewer actor, AnkenScope scope, String epic, List<PhaseRowIn> phases, String reason) {
        TrackerContext ctx = EstimationService.load(context, scope, epic);
        if (reason == null || reason.isBlank()) {
            throw new Invalid("Say why the master schedule changes", "reason");
        }
        List<PhaseRow> rows = compute(ctx, scope, epic, phases);
        versions.add(new ScheduleVersion(0, epic, Instant.now(clock).toString(), actor.email(), reason.strip(), rows));
        return view(ctx, epic);
    }
}
