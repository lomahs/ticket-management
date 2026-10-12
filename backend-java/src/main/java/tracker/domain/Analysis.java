package tracker.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Effort analysis: estimate vs logged on started sub-tasks, and rework by function owner. */
public final class Analysis {
    private Analysis() {
    }

    public record EffortBar(String label, double estimateH, double loggedH) {
    }

    public record Group(String label, List<Issue> issues) {
    }

    public static List<EffortBar> estimateVsLogged(List<Group> groups) {
        List<EffortBar> out = new ArrayList<>();
        for (Group g : groups) {
            List<Issue> started = g.issues().stream().filter(i -> i.status() != Status.NYS).toList();
            out.add(new EffortBar(g.label(), Num.sum(started.stream().mapToDouble(Issue::estimateH)),
                    Num.sum(started.stream().mapToDouble(Issue::loggedH))));
        }
        return out;
    }

    /** @param rate fix hours ÷ create hours */
    public record Rework(String memberId, int functions, double createH, double fixH, double rate, double avgRounds,
                         int maxRounds) {
    }

    public static List<Rework> reworkByMember(List<Function> functions, List<String> memberIds, Map<String, Integer> rounds) {
        List<Rework> out = new ArrayList<>();
        for (String mid : memberIds) {
            List<Function> fns = functions.stream().filter(f -> Objects.equals(f.ownerId(), mid)).toList();
            List<Issue> cells = fns.stream().flatMap(f -> f.issues().stream()).toList();
            double create = Num.sum(cells.stream().filter(c -> c.step() == StepKind.CREATE).mapToDouble(Issue::loggedH));
            double fix = Num.sum(cells.stream().filter(Steps::isFixStep).mapToDouble(Issue::loggedH));
            List<Integer> rs = cells.stream().filter(c -> Steps.isFixStep(c) && rounds.getOrDefault(c.key(), 0) > 0)
                    .map(c -> rounds.getOrDefault(c.key(), 0)).toList();
            double avg = rs.isEmpty() ? 0 : Num.round(rs.stream().mapToInt(Integer::intValue).sum() / (double) rs.size(), 2);
            out.add(new Rework(mid, fns.size(), create, fix, create != 0 ? Num.round(fix / create, 4) : 0, avg,
                    rs.stream().mapToInt(Integer::intValue).max().orElse(0)));
        }
        return out;
    }
}
