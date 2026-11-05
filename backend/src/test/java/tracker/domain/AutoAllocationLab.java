package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import tracker.domain.AutoAllocator.Input;
import tracker.domain.AutoAllocator.Options;
import tracker.domain.AutoAllocator.Phase;
import tracker.domain.AutoAllocator.Pick;
import tracker.domain.AutoAllocator.Row;
import tracker.domain.AutoAllocator.Score;
import tracker.domain.DetailScheduler.Chain;
import tracker.domain.DetailScheduler.Result;

/**
 * The auto allocation lab: random anken (team sizes, allocations that change, VN/JP holidays, full and half-day leave, work
 * already in Jira, people already picked) and every strategy on each, scored by {@link AutoAllocator#score}. Not part of the
 * build: {@code ./mvnw test -Dtest=AutoAllocationLab -Dlab=true}. docs/auto-allocation.md keeps the results.
 */
@EnabledIfSystemProperty(named = "lab", matches = "true")
class AutoAllocationLab {
    static final String EPIC = "E";
    static final String START = "2026-11-02"; // a Monday

    /** A team size: BAs, BALs, JMs and rows. */
    record Size(String name, int bas, int bals, int jms, int rows) {
    }

    static final List<Size> SIZES = List.of(new Size("S  3 BA·1 BAL·1 JM· 20 rows", 3, 1, 1, 20),
            new Size("M  6 BA·2 BAL·2 JM· 50 rows", 6, 2, 2, 50), new Size("L 12 BA·3 BAL·3 JM·100 rows", 12, 3, 3, 100),
            new Size("XL 20 BA·4 BAL·3 JM·160 rows", 20, 4, 3, 160));

    /**
     * What-if knobs, one factor at a time, to see what makes a plan late.
     *
     * @param offDays    holidays and leave
     * @param jiraLoad   work already in Jira
     * @param balFactor  × the BALs' hours
     * @param end        how the master schedule computes a phase's End
     */
    record Variant(String name, boolean offDays, boolean jiraLoad, double balFactor, End end) {
        static final Variant BASE = new Variant("as generated", true, true, 1, End.BA_AND_CHAIN);
    }

    /** POOLED: the whole team's hours against every step but JM Review (before 2026-11); BA_HOURS: the BAs' hours against the BA work. */
    enum End { POOLED, BA_HOURS, BA_AND_CHAIN }

    static Input scenario(Size size, long seed) {
        return scenario(size, seed, Variant.BASE);
    }

    static Input scenario(Size size, long seed, Variant v) {
        Random rnd = new Random(seed);
        Map<String, Member> members = new LinkedHashMap<>();
        Map<String, Role> roles = new HashMap<>();
        List<String> bas = new ArrayList<>();
        List<String> bals = new ArrayList<>();
        List<String> jms = new ArrayList<>();
        for (int i = 1; i <= size.bas(); i++) {
            bas.add(member(members, roles, "ba" + i, Role.BA, rnd.nextInt(5) == 0 ? "JP" : "VN"));
        }
        for (int i = 1; i <= size.bals(); i++) {
            bals.add(member(members, roles, "bal" + i, Role.BAL, "VN"));
        }
        for (int i = 1; i <= size.jms(); i++) {
            jms.add(member(members, roles, "jm" + i, Role.JM, "JP"));
        }
        List<Allocation> alloc = new ArrayList<>();
        int[] baHours = {2, 4, 4, 6, 8, 8};
        int[] balHours = {2, 4, 6};
        for (String id : bas) {
            int h = baHours[rnd.nextInt(baHours.length)];
            alloc.add(new Allocation(id, EPIC, "2026-10-01", h));
            if (rnd.nextInt(4) == 0) { // moves to another anken for part of the time
                alloc.add(new Allocation(id, EPIC, Dates.addDays(START, 14 + rnd.nextInt(14)), Math.max(1, h / 2)));
            }
        }
        for (String id : bals) {
            alloc.add(new Allocation(id, EPIC, "2026-10-01", balHours[rnd.nextInt(balHours.length)] * v.balFactor()));
        }
        Map<String, Holiday> holidays = new HashMap<>();
        String vn = Dates.addDays(START, 7 + rnd.nextInt(5));
        String jp = Dates.addDays(START, 15 + rnd.nextInt(5));
        holidays.put(vn, new Holiday(vn, List.of("VN"), "VN holiday"));
        holidays.put(jp, new Holiday(jp, List.of("JP"), "JP holiday"));
        List<Leave> leaves = new ArrayList<>();
        int n = 0;
        for (String id : members.keySet()) {
            for (int k = rnd.nextInt(4); k > 0; k--) {
                leaves.add(new Leave(++n, id, Dates.addDays(START, rnd.nextInt(42)), LeaveType.FULL, ""));
            }
            for (int k = rnd.nextInt(3); k > 0; k--) {
                leaves.add(new Leave(++n, id, Dates.addDays(START, rnd.nextInt(42)), LeaveType.HALF, ""));
            }
        }
        WorkCalendar cal = v.offDays() ? new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), holidays, members, leaves)
                : new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), members, List.of());
        Allocations a = new Allocations(alloc);
        StepWeights w = StepWeights.defaults();

        // rows over three phases, a tenth with their BA already picked
        List<String> phaseNames = List.of("P1", "P2", "P3");
        Map<String, List<String>> teams = new HashMap<>();
        for (String p : phaseNames) {
            List<String> team = new ArrayList<>();
            bas.forEach(id -> {
                if (rnd.nextInt(10) < 7) {
                    team.add(id);
                }
            });
            if (team.isEmpty()) {
                team.add(bas.get(rnd.nextInt(bas.size())));
            }
            bals.forEach(id -> {
                if (rnd.nextInt(10) < 7) {
                    team.add(id);
                }
            });
            if (team.stream().noneMatch(bals::contains)) {
                team.add(bals.get(rnd.nextInt(bals.size())));
            }
            teams.put(p, team);
        }
        List<Row> rows = new ArrayList<>();
        Map<String, Double> effort = new HashMap<>();
        List<EstimationRow> estimation = new ArrayList<>();
        int[] create = {2, 4, 6, 8, 12, 16};
        int[] ticket = {4, 8, 16, 24};
        for (int i = 0; i < size.rows(); i++) {
            String p = phaseNames.get(Math.min(2, i * 3 / size.rows()));
            int kind = rnd.nextInt(20);
            Integer steps = kind < 8 ? Integer.valueOf(5) : kind < 15 ? Integer.valueOf(3) : null;
            double e = steps == null ? ticket[rnd.nextInt(ticket.length)] : create[rnd.nextInt(create.length)];
            List<String> teamBas = teams.get(p).stream().filter(bas::contains).toList();
            String ba = rnd.nextInt(10) == 0 ? teamBas.get(rnd.nextInt(teamBas.size())) : null;
            rows.add(new Row(i + 1, p, steps, e, ba, null, null));
            effort.merge(p, steps == null ? e : w.baTotal(e, steps) + w.stepHours(e, StepKind.BAL_REVIEW), Double::sum);
            estimation.add(new EstimationRow(i + 1, EPIC, i, p, "", "", steps, e, null, null, null, null, null));
        }
        // the master schedule, as Master schedule computes it: phases chained, each ends when its team gave its effort
        Map<String, Phase> phases = new LinkedHashMap<>();
        Map<String, MasterSchedule.PhaseWork> work = MasterSchedule.work(estimation, w, 8);
        String start = START;
        for (String p : phaseNames) {
            List<String> teamBas = teams.get(p).stream().filter(bas::contains).toList();
            MasterSchedule.PhaseWork pw = work.get(p);
            MasterSchedule.PhaseEnd end = switch (v.end()) {
                case POOLED -> MasterSchedule.endOf(EPIC, start, effort.getOrDefault(p, 0.0), teams.get(p), a, cal);
                case BA_HOURS -> MasterSchedule.endOf(EPIC, start, pw.effortH(), teamBas, a, cal);
                case BA_AND_CHAIN -> MasterSchedule.endOf(EPIC, start, pw, teamBas, a, cal);
            };
            phases.put(p, new Phase(start, end.end(), teams.get(p)));
            start = Dates.addDays(end.end(), 1);
        }
        // work already in Jira: a third of the BAs have 2h a day for the first week
        Map<String, Map<String, Double>> loads = new HashMap<>();
        for (String id : bas) {
            if (rnd.nextInt(3) == 0 && v.jiraLoad()) {
                Map<String, Double> days = new TreeMap<>();
                for (int d = 0; d < 5; d++) {
                    days.put(Dates.addDays(START, d), 2.0);
                }
                loads.put(id, days);
            }
        }
        return new Input(EPIC, rows, phases, roles, jms, loads, Map.of(), AutoAllocator.cached(DetailScheduler.hours(EPIC, a, cal)), cal, w, 8);
    }

    /** One strategy on each variant: how much each factor adds to the days late. */
    @Test
    void blockers() {
        List<Variant> variants = List.of(new Variant("End pooled (before)", true, true, 1, End.POOLED),
                new Variant("End from BA hours", true, true, 1, End.BA_HOURS), Variant.BASE,
                new Variant("no holidays or leave", false, true, 1, End.BA_AND_CHAIN),
                new Variant("no work already in Jira", true, false, 1, End.BA_AND_CHAIN),
                new Variant("BALs ×3 hours", true, true, 3, End.BA_AND_CHAIN));
        Options o = Options.DEFAULT;
        StringBuilder out = new StringBuilder("\n| variant | size | over budget h | days late | days early | phase days | tail days |\n|---|---|---|---|---|---|---|\n");
        for (Variant v : variants) {
            for (Size size : SIZES.subList(0, 3)) {
                double[] sum = new double[5];
                for (long seed = 1; seed <= SEEDS; seed++) {
                    Input in = scenario(size, seed * 7919, v);
                    List<Pick> picks = AutoAllocator.allocate(in, o);
                    Score sc = AutoAllocator.score(in, picks);
                    sum[0] += sc.overBudgetH();
                    sum[1] += sc.daysLate();
                    sum[2] += sc.phaseDays();
                    sum[3] += tail(in, picks);
                    sum[4] += early(in, picks);
                }
                out.append(String.format("| %s | %s | %.1f | %.1f | %.1f | %.1f | %.1f |%n", v.name(), size.name().substring(0, 2).trim(),
                        sum[0] / SEEDS, sum[1] / SEEDS, sum[4] / SEEDS, sum[2] / SEEDS, sum[3] / SEEDS));
            }
        }
        System.out.println("\n=== BLOCKERS ===" + out);
    }

    /** Σ over phases of calendar days from the last Create (or ticket) to the phase's last step: the review chain's tail. */
    static double tail(Input in, List<Pick> picks) {
        List<Chain> chains = new ArrayList<>();
        for (int i = 0; i < in.rows().size(); i++) {
            Chain c = AutoAllocator.chain(in, in.rows().get(i), picks.get(i));
            if (c != null) {
                chains.add(c);
            }
        }
        Map<String, String> lastWork = new HashMap<>();
        Map<String, String> lastAny = new HashMap<>();
        for (DetailScheduler.Placed p : DetailScheduler.schedule(EPIC, chains, in.loads(), in.hours(), in.cal(), in.jmHoursPerDay()).placed()) {
            lastAny.merge(p.phase(), p.end(), Dates::max);
            if (p.kind() == null || p.kind() == StepKind.CREATE) {
                lastWork.merge(p.phase(), p.end(), Dates::max);
            }
        }
        return lastAny.keySet().stream().mapToInt(ph -> Dates.dayDiff(lastWork.getOrDefault(ph, lastAny.get(ph)), lastAny.get(ph))).sum();
    }

    /** Σ over phases of calendar days the plan ends before the phase's End: how much the End over-reserves. */
    static double early(Input in, List<Pick> picks) {
        List<Chain> chains = new ArrayList<>();
        for (int i = 0; i < in.rows().size(); i++) {
            Chain c = AutoAllocator.chain(in, in.rows().get(i), picks.get(i));
            if (c != null) {
                chains.add(c);
            }
        }
        Map<String, String> last = new HashMap<>();
        for (DetailScheduler.Placed p : DetailScheduler.schedule(EPIC, chains, in.loads(), in.hours(), in.cal(), in.jmHoursPerDay()).placed()) {
            last.merge(p.phase(), p.end(), Dates::max);
        }
        return last.entrySet().stream().mapToInt(e -> Math.max(0, Dates.dayDiff(e.getValue(), in.phases().get(e.getKey()).end()))).sum();
    }

    static String member(Map<String, Member> members, Map<String, Role> roles, String id, Role role, String country) {
        members.put(id, new Member(id, id, id, role, country, "#000"));
        roles.put(id, role);
        return id;
    }

    /** The first version (commit 356afc4): row after row the combination that ends the row soonest, then the fewest hours planned. */
    static List<Pick> baseline(Input in) {
        Map<String, Map<String, Double>> ledger = in.loads();
        List<Pick> out = new ArrayList<>();
        for (Row r : in.rows()) {
            List<AutoAllocator.Slot> open = AutoAllocator.open(in, r);
            List<List<String>> choices = open.stream().map(s -> AutoAllocator.candidates(in, r, s)).toList();
            if (choices.stream().anyMatch(List::isEmpty)) {
                out.add(AutoAllocator.initial(r));
                continue;
            }
            List<Pick> combos = new ArrayList<>(List.of(AutoAllocator.initial(r)));
            for (int i = 0; i < open.size(); i++) {
                List<Pick> next = new ArrayList<>();
                for (Pick p : combos) {
                    for (String id : choices.get(i)) {
                        next.add(p.with(open.get(i), id));
                    }
                }
                combos = next;
            }
            Pick best = null;
            Result bestRes = null;
            String bestEnd = null;
            double bestLoad = 0;
            for (Pick p : combos) {
                Chain c = AutoAllocator.chain(in, r, p);
                if (c == null) {
                    continue;
                }
                Result res = DetailScheduler.schedule(EPIC, List.of(c), ledger, in.hours(), in.cal(), in.jmHoursPerDay());
                String end = res.placed().stream().map(DetailScheduler.Placed::end).max(Comparator.naturalOrder()).orElseThrow();
                double load = load(ledger, p.baId()) + load(ledger, p.balId()) + load(ledger, p.jmId());
                if (best == null || end.compareTo(bestEnd) < 0 || end.equals(bestEnd) && load < bestLoad) {
                    best = p;
                    bestRes = res;
                    bestEnd = end;
                    bestLoad = load;
                }
            }
            if (best == null) {
                out.add(AutoAllocator.initial(r));
            } else {
                ledger = bestRes.ledger();
                out.add(best);
            }
        }
        return out;
    }

    static double load(Map<String, Map<String, Double>> ledger, String id) {
        return id == null ? 0 : ledger.getOrDefault(id, Map.of()).values().stream().mapToDouble(Double::doubleValue).sum();
    }

    static final Map<String, Function<Input, List<Pick>>> STRATEGIES = new LinkedHashMap<>();

    static {
        STRATEGIES.put("baseline", AutoAllocationLab::baseline);
        String only = System.getProperty("lab.only", "");
        Map<String, Options> all = new LinkedHashMap<>();
        AutoAllocator.Key e = AutoAllocator.Key.END_BUDGET;
        AutoAllocator.Moves all_ = AutoAllocator.Moves.ALL;
        AutoAllocator.Moves tta = AutoAllocator.Moves.TARGETED_THEN_ALL;
        all.put("greedy", Options.GREEDY);
        all.put("full: improve+swaps", new Options(e, true, true, all_, 20));
        all.put("targeted→all, no cap", new Options(e, true, true, tta, 20));
        for (int cap : new int[]{2000, 4000, 8000}) {
            all.put("targeted→all ≤" + cap, new Options(e, true, true, tta, 20, cap, 0, false));
        }
        all.put("DEFAULT", Options.DEFAULT);
        all.forEach((name, o) -> {
            if (only.isEmpty() || name.matches(only)) {
                STRATEGIES.put(name, in -> AutoAllocator.allocate(in, o));
            }
        });
    }

    static final int SEEDS = Integer.getInteger("lab.seeds", 12);

    @Test
    void compareStrategies() {
        StringBuilder out = new StringBuilder();
        for (Size size : SIZES) {
            Map<String, double[]> sums = new LinkedHashMap<>();
            Map<String, Integer> wins = new LinkedHashMap<>();
            for (long seed = 1; seed <= SEEDS; seed++) {
                Input in = scenario(size, seed * 7919);
                Map<String, Score> scores = new LinkedHashMap<>();
                for (Map.Entry<String, Function<Input, List<Pick>>> s : STRATEGIES.entrySet()) {
                    long t0 = System.nanoTime();
                    List<Pick> picks = s.getValue().apply(in);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    Score sc = AutoAllocator.score(in, picks);
                    scores.put(s.getKey(), sc);
                    double[] sum = sums.computeIfAbsent(s.getKey(), k -> new double[6]);
                    sum[0] += sc.unfilled();
                    sum[1] += sc.overBudgetH();
                    sum[2] += sc.daysLate();
                    sum[3] += sc.phaseDays();
                    sum[4] += sc.spreadH();
                    sum[5] += ms;
                }
                Score best = scores.values().stream().min(Comparator.naturalOrder()).orElseThrow();
                scores.forEach((k, v) -> wins.merge(k, v.compareTo(best) == 0 ? 1 : 0, Integer::sum));
            }
            out.append("\n### ").append(size.name()).append(" (").append(SEEDS).append(" anken)\n\n")
                    .append("| strategy | best on | unfilled | over budget h | days late | phase days | spread h | ms |\n")
                    .append("|---|---|---|---|---|---|---|---|\n");
            sums.forEach((k, s) -> out.append(String.format("| %s | %d/%d | %.1f | %.1f | %.1f | %.1f | %.1f | %.0f |%n", k, wins.get(k), SEEDS,
                    s[0] / SEEDS, s[1] / SEEDS, s[2] / SEEDS, s[3] / SEEDS, s[4] / SEEDS, s[5] / SEEDS)));
        }
        System.out.println("\n=== AUTO ALLOCATION LAB ===" + out);
    }
}
