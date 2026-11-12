package tracker.application.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.PlanDayRepository;
import tracker.application.ports.SettingsRepository;
import tracker.domain.AnkenScope;
import tracker.domain.DetailScheduler;
import tracker.domain.EstimationRow;
import tracker.domain.Issue;
import tracker.domain.StepKind;

/**
 * Links the hours per day of rows pushed before the push kept them: each step of a pushed row goes to the sub-task under the
 * row's Task with the same function name and step. A step matching no sub-task, or several, is left unlinked.
 */
@Service
public class PlanDayLinker {
    private final TrackerContextLoader context;
    private final SettingsRepository settings;
    private final EstimationRepository estimation;
    private final PlanDayRepository planDays;

    public PlanDayLinker(TrackerContextLoader context, SettingsRepository settings, EstimationRepository estimation, PlanDayRepository planDays) {
        this.context = context;
        this.settings = settings;
        this.estimation = estimation;
        this.planDays = planDays;
    }

    /** Links what is not linked yet, in every tracked anken; a sub-task that already has hours per day keeps them. */
    public void linkPushed() {
        List<String> epics = settings.load().jira.epics;
        TrackerContext ctx = context.load(new AnkenScope(Set.copyOf(epics)));
        for (String epic : epics) {
            List<DetailScheduler.Part> parts = estimation.pushedParts(epic);
            if (parts.isEmpty()) {
                continue;
            }
            List<Issue> subs = ctx.subtasks().stream().filter(i -> i.epicKey().equals(epic)).toList();
            Set<String> linked = new HashSet<>(planDays.linked(subs.stream().map(Issue::key).toList()));
            Set<String> refs = new HashSet<>();
            parts.forEach(p -> refs.add(p.ref()));
            for (EstimationRow r : estimation.findByEpic(epic)) {
                if (!r.pushed() || r.taskKey() == null) {
                    continue;
                }
                for (String ref : refs) {
                    if (rowOf(ref) != r.id()) {
                        continue;
                    }
                    Issue match = match(subs, r, stepOf(ref));
                    if (match != null && linked.add(match.key())) {
                        planDays.save(match.key(), DetailPlanService.hoursByDay(parts, ref));
                    }
                }
            }
        }
    }

    private static @Nullable Issue match(List<Issue> subs, EstimationRow r, @Nullable StepKind step) {
        List<Issue> found = new ArrayList<>();
        for (Issue i : subs) {
            if (r.taskKey().equals(i.parentKey()) && step == i.step() && norm(r.functionName()).equals(norm(i.functionName()))) {
                found.add(i);
            }
        }
        return found.size() == 1 ? found.getFirst() : null;
    }

    private static String norm(@Nullable String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    /** "12:create" → 12; "12" (a row without steps) → 12. */
    private static long rowOf(String ref) {
        int i = ref.indexOf(':');
        return Long.parseLong(i < 0 ? ref : ref.substring(0, i));
    }

    private static @Nullable StepKind stepOf(String ref) {
        int i = ref.indexOf(':');
        return i < 0 ? null : StepKind.of(ref.substring(i + 1));
    }
}
