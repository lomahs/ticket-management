package tracker.domain;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An anken onboarded before its epic exists in Jira. It is known by a key no Jira key can be ({@code draft-1}: a Jira key is upper
 * case) and is seen in Onboarding only. Until its epic is linked, its Tasks are the template's, each under a key of its own
 * ({@code template:} and the template's match), so the estimation and the detail plan work as they do under an epic.
 */
public final class DraftAnken {
    private DraftAnken() {
    }

    public static final String KEY_PREFIX = "draft-";
    public static final String TASK_PREFIX = "template:";

    public static boolean isKey(@Nullable String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    public static String key(int n) {
        return KEY_PREFIX + n;
    }

    public static boolean isTask(@Nullable String key) {
        return key != null && key.startsWith(TASK_PREFIX);
    }

    public static String taskKey(TaskTemplate t) {
        return TASK_PREFIX + t.match();
    }

    /** The template match a draft Task's key stands for; null for any other key. */
    public static @Nullable String matchOf(@Nullable String key) {
        return isTask(key) ? key.substring(TASK_PREFIX.length()) : null;
    }

    /**
     * The name a template Task gets: the Task pattern's, or, when the template would not recognise that name, the template's match
     * with each {@code *} replaced by the prefix.
     */
    public static String taskName(String rule, String ankenPrefix, TaskTemplate t, WorkStructure st) {
        return TaskNames.recognised(rule, ankenPrefix, t, st) ? TaskNames.render(rule, ankenPrefix, t) : t.match().replace("*", ankenPrefix.strip());
    }

    /** The draft's Tasks, one per template Task, named for {@code ankenPrefix}; none until the prefix is entered. */
    public static List<Issue> tasks(String draft, WorkStructure st, String rule, String ankenPrefix, String created) {
        if (ankenPrefix.isBlank()) {
            return List.of();
        }
        return st.tasks().stream().map(t -> new Issue(taskKey(t), "", draft, draft, IssueKind.PARENT, taskName(rule, ankenPrefix, t, st), t.phase(),
                Status.NYS, null, null, null, 0, 0, created, "", null, null, null, List.of(), List.of())).toList();
    }
}
