package tracker.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.JiraIssueType;
import tracker.application.dto.JiraTransition;

/** Reads a Jira site. Failures to reach it raise {@link JiraError}. */
public interface JiraReader {
    /** Parent tickets of the epics and their sub-tasks, optionally only those updated since a moment (null: all). */
    List<IssueSnapshot> search(List<String> epicKeys, @Nullable Instant updatedSince);

    /** The epic, or empty when there is no such issue or it is not an epic. */
    Optional<EpicSnapshot> findEpic(String key);

    Optional<IssueSnapshot> findIssue(String key);

    List<JiraField> listFields();

    boolean testConnection();

    /** The transitions Jira offers from the issue's current status. */
    List<JiraTransition> transitions(String key);

    /** The issue types the project can create. */
    List<JiraIssueType> issueTypes(String project);
}
