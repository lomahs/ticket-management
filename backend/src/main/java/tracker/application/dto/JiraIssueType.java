package tracker.application.dto;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/** An issue type a project can create; {@code subtask} marks the sub-task types. */
public record JiraIssueType(String id, String name, boolean subtask) {
    /**
     * The sub-task type a new sub-task gets when none is picked: the one named Sub-task (or Subtask), else the first. A project
     * can have more than one, e.g. a Bug sub-task type listed before Sub-task.
     */
    public static @Nullable String defaultSubtask(List<JiraIssueType> types) {
        List<JiraIssueType> subtasks = types.stream().filter(JiraIssueType::subtask).toList();
        return subtasks.stream().filter(t -> t.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").equals("subtask")).findFirst()
                .or(() -> subtasks.stream().findFirst()).map(JiraIssueType::id).orElse(null);
    }
}
