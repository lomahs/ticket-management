package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import tracker.application.TaskCard;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.domain.AnkenScope;
import tracker.domain.Issue;
import tracker.domain.PhaseGroups;
import tracker.domain.TicketChecks;

/** Ticket Check: the sub-tasks of the anken in scope whose Jira data makes the other screens wrong, with what is wrong. */
@Service
public class TicketCheckService {
    private final TrackerContextLoader context;

    public TicketCheckService(TrackerContextLoader context) {
        this.context = context;
    }

    /** @param check one of {@link TicketChecks#ALL} */
    public record CheckCount(String check, int count) {
    }

    /**
     * @param taskName the Task (sub-phase) without its leading brackets
     * @param problems in {@link TicketChecks#ALL} order
     */
    public record CheckRow(TaskCard card, String epicKey, String taskKey, String taskName, List<String> problems) {
    }

    /** @param checked the sub-tasks looked at; @param counts every check, also those with no ticket */
    public record TicketCheckView(int checked, List<CheckCount> counts, List<CheckRow> rows) {
    }

    public TicketCheckView view(AnkenScope scope) {
        TrackerContext ctx = context.load(scope);
        Set<String> outOfOrder = new HashSet<>();
        ctx.functions.forEach(f -> outOfOrder.addAll(TicketChecks.outOfOrder(f)));
        Map<String, Issue> parents = new HashMap<>();
        ctx.parents().forEach(p -> parents.put(p.key(), p));
        Map<String, Integer> order = new HashMap<>();
        for (int n = 0; n < ctx.settings.jira.epics.size(); n++) {
            order.put(ctx.settings.jira.epics.get(n), n);
        }
        Map<String, Integer> counts = new HashMap<>();
        List<CheckRow> rows = new ArrayList<>();
        List<Issue> subs = ctx.subtasks();
        for (Issue i : subs) {
            boolean member = i.assigneeId() == null || ctx.isMember(i.epicKey(), i.assigneeId());
            List<String> problems = TicketChecks.of(i, member, outOfOrder.contains(i.key()));
            if (problems.isEmpty()) {
                continue;
            }
            problems.forEach(p -> counts.merge(p, 1, Integer::sum));
            Issue task = i.parentKey() != null ? parents.get(i.parentKey()) : null;
            rows.add(new CheckRow(ctx.card(i), i.epicKey(), task != null ? task.key() : "", task != null ? PhaseGroups.taskName(task.summary()) : "",
                    problems));
        }
        rows.sort(Comparator.comparingInt((CheckRow r) -> order.getOrDefault(r.epicKey(), 99)).thenComparing(CheckRow::taskKey)
                .thenComparing(r -> r.card().key()));
        return new TicketCheckView(subs.size(), TicketChecks.ALL.stream().map(c -> new CheckCount(c, counts.getOrDefault(c, 0))).toList(),
                rows);
    }
}
