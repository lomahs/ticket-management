package tracker.application.ports;

import java.util.List;

import org.jspecify.annotations.Nullable;

import tracker.application.dto.NewSubtask;
import tracker.domain.Status;

/** Writes to a Jira site (the demo site only, in phase 1). */
public interface JiraWriter {
    /** Null arguments leave the field as it is. */
    void updateIssue(String key, @Nullable String plannedStart, @Nullable String plannedEnd, @Nullable String assigneeAccountId);

    void transition(String key, Status status);

    List<String> createSubtasks(String parentKey, List<NewSubtask> subtasks);
}
