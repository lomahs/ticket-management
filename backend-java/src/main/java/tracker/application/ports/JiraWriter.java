package tracker.application.ports;

import java.util.List;

import tracker.application.dto.NewSubtask;
import tracker.domain.Status;

public interface JiraWriter {
    /** Null arguments leave the field as it is. */
    void updateIssue(String key, String plannedStart, String plannedEnd, String assigneeAccountId);

    void transition(String key, Status status);

    List<String> createSubtasks(String parentKey, List<NewSubtask> subtasks);
}
