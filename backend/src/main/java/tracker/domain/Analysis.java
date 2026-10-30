package tracker.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/** Effort analysis: estimate vs logged on started sub-tasks, and rework by the role of the worklog's author. */
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
            out.add(new EffortBar(g.label(), started.stream().mapToDouble(Issue::estimateH).sum(),
                    started.stream().mapToDouble(Issue::loggedH).sum()));
        }
        return out;
    }

    /**
     * One member's effort on the epic's function steps, from the worklogs they wrote, by kind ({@link Effort#kindOf}).
     *
     * @param functions the functions they are BA or BAL of
     * @param rate      fix hours ÷ create hours; 0 without create hours
     * @param avgRounds review rounds over the Fix comment steps (with a round) of their functions
     */
    public record Rework(String memberId, @Nullable Role role, int functions, double createH, double fixH, double reviewH, double rate,
                         double avgRounds, int maxRounds) {
    }

    /**
     * The BAs and BALs with a Rework row: the BA or BAL of a function, or the author of a worklog on one of its steps (so a BA
     * who handed a function over keeps the hours they logged on it).
     */
    public static Set<String> reworkPeople(List<Function> functions, Map<String, Member> members) {
        Set<String> out = new LinkedHashSet<>();
        for (Function f : functions) {
            Stream.of(f.baId(members), f.balId(members)).filter(Objects::nonNull).forEach(out::add);
            f.issues().forEach(c -> c.worklogs().forEach(w -> out.add(w.memberId())));
        }
        out.removeIf(id -> {
            Member m = members.get(id);
            return m == null || (m.role() != Role.BA && m.role() != Role.BAL);
        });
        return out;
    }

    /** Invalid worklogs and worklogs by someone else are in no member's row. */
    public static List<Rework> reworkByMember(List<Function> functions, List<String> memberIds, Map<String, Member> members,
                                              Map<String, Integer> rounds) {
        List<Rework> out = new ArrayList<>();
        for (String mid : memberIds) {
            Member m = members.get(mid);
            Role role = m != null ? m.role() : null;
            List<Function> fns = functions.stream()
                    .filter(f -> mid.equals(f.baId(members)) || mid.equals(f.balId(members))).toList();
            Map<Effort.WorkKind, Double> hours = new EnumMap<>(Effort.WorkKind.class);
            for (Function f : functions) {
                for (Issue c : f.issues()) {
                    for (Worklog w : c.worklogs()) {
                        if (mid.equals(w.memberId())) {
                            hours.merge(Effort.kindOf(c.step(), role), w.hours(), Double::sum);
                        }
                    }
                }
            }
            double create = Numbers.round(hours.getOrDefault(Effort.WorkKind.CREATE, 0.0), 2);
            double fix = Numbers.round(hours.getOrDefault(Effort.WorkKind.FIX, 0.0), 2);
            double review = Numbers.round(hours.getOrDefault(Effort.WorkKind.REVIEW, 0.0), 2);
            List<Integer> rs = fns.stream().flatMap(f -> f.issues().stream())
                    .filter(c -> Steps.isFixStep(c) && rounds.getOrDefault(c.key(), 0) > 0)
                    .map(c -> rounds.getOrDefault(c.key(), 0)).toList();
            double avg = rs.isEmpty() ? 0 : Numbers.round(rs.stream().mapToInt(Integer::intValue).sum() / (double) rs.size(), 2);
            out.add(new Rework(mid, role, fns.size(), create, fix, review, create != 0 ? Numbers.round(fix / create, 4) : 0, avg,
                    rs.stream().mapToInt(Integer::intValue).max().orElse(0)));
        }
        return out;
    }
}
