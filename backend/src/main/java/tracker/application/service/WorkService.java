package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.NotFound;
import tracker.application.TaskCard;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.domain.AnkenScope;
import tracker.domain.Dates;
import tracker.domain.Function;
import tracker.domain.Issue;
import tracker.domain.IssueFlags;
import tracker.domain.Member;
import tracker.domain.Numbers;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepDef;
import tracker.domain.Steps;
import tracker.domain.Text;

/** My Work: a member's deadlines, backlog and the related steps of functions they own or review. */
@Service
public class WorkService {
    private final TrackerContextLoader context;

    public WorkService(TrackerContextLoader context) {
        this.context = context;
    }

    /**
     * @param kind  overdue | week | month | unplanned
     * @param start week start (YYYY-MM-DD) or month (YYYY-MM)
     */
    public record BacklogGroup(String kind, @Nullable String start, boolean current, List<TaskCard> items, double remainingH) {
    }

    /** @param relatedScope owner | reviewer */
    public record MyWork(String memberId, String today, String nextDay, List<TaskCard> delay, List<TaskCard> dueToday,
                         List<TaskCard> dueNext, List<BacklogGroup> backlog, List<TaskCard> related, String relatedScope,
                         int openCount, double remainingH) {
    }

    private static final Comparator<Issue> BY_END = Comparator
            .comparing((Issue i) -> Text.orElse(i.plannedEnd(), "9999")).thenComparing(Issue::key);

    private static boolean isReviewItem(TrackerContext ctx, Issue i) {
        StepDef step = Steps.stepDef(i);
        if (step == null) {
            return false;
        }
        IssueFlags f = ctx.flags.get(i.key());
        return (step.review() && f.isCurrent() && (i.status() == Status.NYS || i.status() == Status.WIP))
                || (step.fix() && i.status() == Status.RESOLVED);
    }

    private record GroupKey(int rank, String kind, String start) {
    }

    private static double remaining(TrackerContext ctx, List<Issue> items) {
        return Numbers.round(items.stream().mapToDouble(i -> ctx.card(i).remainingH()).sum(), 2);
    }

    /**
     * Only the sub-tasks of the anken in scope are listed. The member must be one of the people of the scope, or the viewer's own
     * member ({@code viewerMemberId}), who always has a My Work page even without a membership.
     */
    public MyWork myWork(AnkenScope scope, String memberId, String backlog, @Nullable String viewerMemberId) {
        TrackerContext ctx = context.load(scope);
        String today = ctx.today;
        Member me = ctx.members.get(memberId);
        if (me == null || !(ctx.people.contains(memberId) || memberId.equals(viewerMemberId))) {
            throw new NotFound("Member " + memberId + " was not found", "member");
        }
        List<Issue> mine = new ArrayList<>(ctx.subtasks().stream()
                .filter(i -> Objects.equals(i.assigneeId(), memberId) && i.status() != Status.CLOSED).toList());
        mine.sort(BY_END);
        List<Issue> tasks = mine.stream().filter(i -> i.status() != Status.RESOLVED || isReviewItem(ctx, i)).toList();
        String nextDay = ctx.cal.nextWork(Dates.addDays(today, 1), memberId);

        Map<GroupKey, List<Issue>> groups = new LinkedHashMap<>();
        String thisWeek = Dates.mondayOf(today);
        String thisMonth = today.substring(0, 7);
        for (Issue i : mine) {
            String pe = i.plannedEnd();
            GroupKey k;
            if (pe == null) {
                k = new GroupKey(3, "unplanned", null);
            } else if (ctx.flags.get(i.key()).late()) {
                k = new GroupKey(0, "overdue", null);
            } else if (backlog.equals("month")) {
                k = new GroupKey(1, "month", pe.substring(0, Math.min(7, pe.length())));
            } else {
                k = new GroupKey(1, "week", Dates.mondayOf(pe));
            }
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
        }
        List<Map.Entry<GroupKey, List<Issue>>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.comparing((Map.Entry<GroupKey, List<Issue>> e) -> e.getKey().rank())
                .thenComparing(e -> e.getKey().start() == null ? "" : e.getKey().start()));
        List<BacklogGroup> backlogGroups = sorted.stream().map(e -> {
            GroupKey k = e.getKey();
            boolean current = k.start() != null && (k.start().equals(thisWeek) || k.start().equals(thisMonth));
            return new BacklogGroup(k.kind(), k.start(), current, e.getValue().stream().map(ctx::card).toList(),
                    remaining(ctx, e.getValue()));
        }).toList();

        List<Issue> related = new ArrayList<>();
        String relatedScope;
        if (me.role() == Role.BAL) {
            for (Function f : ctx.functions) {
                if (Objects.equals(f.reviewerId(), memberId)) {
                    for (int idx : new int[]{0, 2, 3, 4}) {
                        Issue c = f.cells().get(idx);
                        if (c != null) {
                            related.add(c);
                        }
                    }
                }
            }
            relatedScope = "reviewer";
        } else {
            for (Function f : ctx.functions) {
                if (Objects.equals(f.ownerId(), memberId)) {
                    f.cells().subList(1, f.cells().size()).stream().filter(Objects::nonNull).forEach(related::add);
                }
            }
            relatedScope = "owner";
        }
        related = new ArrayList<>(related.stream().filter(c -> c.status() != Status.CLOSED).toList());
        related.sort(BY_END);

        return new MyWork(
                memberId, today, nextDay,
                tasks.stream().filter(i -> ctx.flags.get(i.key()).late()).map(ctx::card).toList(),
                tasks.stream().filter(i -> today.equals(i.plannedEnd())).map(ctx::card).toList(),
                tasks.stream().filter(i -> nextDay.equals(i.plannedEnd())).map(ctx::card).toList(),
                backlogGroups, related.stream().map(ctx::card).toList(), relatedScope,
                mine.size(), remaining(ctx, mine));
    }
}
