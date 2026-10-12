package tracker.application.ports;

import java.time.Instant;
import java.util.List;

import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;

public interface JiraReader {
    /** Parent tickets of the epics and their sub-tasks, optionally only those updated since a moment (null: all). */
    Iterable<IssueSnapshot> search(List<String> epicKeys, Instant updatedSince);

    EpicSnapshot getEpic(String key);

    IssueSnapshot getIssue(String key);

    List<JiraField> listFields();

    boolean testConnection();
}
