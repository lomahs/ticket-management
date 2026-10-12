package tracker.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Rule-based answers for the Task Q&A panel. */
public final class Qa {
    private Qa() {
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public record QaContext(List<Function> functions, Map<String, IssueFlags> flags, Map<String, Member> members,
                            List<Worklog> worklogs, String today, double hoursPerDay, List<Integer> workingDays) {
    }

    public record Answer(String text, List<String> chips) {
    }

    /** Lower case without diacritics. */
    public static String fold(String s) {
        String nfd = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder();
        nfd.codePoints().filter(c -> Character.getType(c) != Character.NON_SPACING_MARK).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** str.split() without arguments: runs of whitespace separate, no empty words. */
    public static List<String> words(String s) {
        String t = s.strip();
        return t.isEmpty() ? List.of() : Arrays.asList(WHITESPACE.split(t));
    }

    private static Member mentioned(String q, Map<String, Member> members, Role role) {
        Set<String> words = new HashSet<>(words(fold(q).replace("?", " ")));
        for (Member m : members.values()) {
            if ((role == null || m.role() == role) && (words.contains(fold(m.name())) || words.contains(m.id()))) {
                return m;
            }
        }
        return null;
    }

    private static Member first(Map<String, Member> members, Role role) {
        return members.values().stream().filter(m -> m.role() == role).findFirst().orElse(null);
    }

    private static List<String> keys(List<Issue> xs) {
        return xs.stream().map(Issue::key).toList();
    }

    public static Answer answer(QaContext ctx, String q) {
        String t = fold(q);
        List<Issue> cells = ctx.functions().stream().flatMap(f -> f.issues().stream()).toList();
        Map<String, String> fid = new LinkedHashMap<>();
        ctx.functions().forEach(f -> f.issues().forEach(c -> fid.put(c.key(), f.id())));
        Map<String, IssueFlags> fl = ctx.flags();
        List<String> tWords = words(t);

        if (t.contains("late")) {
            List<Issue> late = new ArrayList<>(cells.stream().filter(c -> fl.get(c.key()).late()).toList());
            late.sort(Comparator.comparingInt(c -> -fl.get(c.key()).lateDays()));
            if (late.isEmpty()) {
                return new Answer("No sub-tasks are past their plan.", List.of());
            }
            Issue top = late.get(0);
            long fns = late.stream().map(c -> fid.get(c.key())).distinct().count();
            return new Answer(late.size() + " sub-tasks are past their plan, across " + fns + " functions. "
                    + "Longest delay: " + fid.get(top.key()) + " (" + fl.get(top.key()).lateDays() + " working days).", keys(late));
        }

        Member bal = mentioned(q, ctx.members(), Role.BAL);
        if (bal == null && tWords.contains("bal")) {
            bal = first(ctx.members(), Role.BAL);
        }
        if (bal != null) {
            String id = bal.id();
            List<Issue> held = cells.stream().filter(c -> Objects.equals(c.assigneeId(), id) && fl.get(c.key()).isCurrent()
                    && c.status() != Status.CLOSED).toList();
            int longest = held.stream().mapToInt(c -> fl.get(c.key()).heldDays()).max().orElse(0);
            return new Answer(bal.name() + " is holding " + held.size() + " tickets. The longest has been held " + longest + " days.",
                    keys(held));
        }

        if (t.contains("round") || t.contains("ping")) {
            List<Issue> many = new ArrayList<>(cells.stream().filter(c -> fl.get(c.key()).rounds() >= 2).toList());
            many.sort(Comparator.comparingInt(c -> -fl.get(c.key()).rounds()));
            long n3 = many.stream().filter(c -> fl.get(c.key()).rounds() >= 3).count();
            return new Answer(n3 + " Fix comment tickets have gone 3 rounds or more. Sorted by rounds:", keys(many));
        }

        Member jm = mentioned(q, ctx.members(), Role.JM);
        if (jm == null && (tWords.contains("jm") || t.contains("client"))) {
            jm = first(ctx.members(), Role.JM);
        }
        if (jm != null) {
            String id = jm.id();
            List<Issue> waiting = cells.stream().filter(c -> Objects.equals(c.assigneeId(), id) && c.status() != Status.CLOSED
                    && fl.get(c.key()).heldDays() > 0).toList();
            int longest = waiting.stream().mapToInt(c -> fl.get(c.key()).heldDays()).max().orElse(0);
            return new Answer(waiting.size() + " tickets are with " + jm.name() + " (JM). Longest wait: " + longest + " days.",
                    keys(waiting));
        }

        if (t.contains("log") || t.contains("hour")) {
            Member m = mentioned(q, ctx.members(), null);
            if (m == null) {
                return new Answer("Whose hours? Mention a member, e.g. “How many hours has An logged this week?”", List.of());
            }
            String mon = Dates.mondayOf(ctx.today());
            List<String> week = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                week.add(Dates.addDays(mon, i));
            }
            List<String> workdays = week.stream().filter(d -> ctx.workingDays().contains(Dates.weekday(d))).toList();
            String id = m.id();
            List<Worklog> logs = ctx.worklogs().stream().filter(w -> Objects.equals(w.memberId(), id)
                    && mon.compareTo(w.date()) <= 0 && w.date().compareTo(ctx.today()) <= 0).toList();
            double total = Num.sum(logs.stream().mapToDouble(Worklog::hours));
            double todayH = Num.sum(logs.stream().filter(w -> w.date().equals(ctx.today())).mapToDouble(Worklog::hours));
            double expected = ctx.hoursPerDay() * workdays.size();
            List<Issue> open = cells.stream().filter(c -> Objects.equals(c.assigneeId(), id) && c.loggedH() > 0
                    && c.status() != Status.CLOSED).toList();
            return new Answer(m.name() + " has logged " + h(total) + "h / " + h(expected) + "h this week ("
                    + Dates.dm(workdays.get(0)) + "–" + Dates.dm(workdays.get(workdays.size() - 1)) + "), " + h(todayH) + "h today.",
                    keys(open));
        }

        return new Answer("I did not understand that. Try asking about late tasks, review tickets, rounds, waiting on JM or logged hours.",
                List.of());
    }

    private static String h(double x) {
        double v = Dates.oneDecimal(x);
        return v == (long) v ? Long.toString((long) v) : Py.repr(v);
    }
}
