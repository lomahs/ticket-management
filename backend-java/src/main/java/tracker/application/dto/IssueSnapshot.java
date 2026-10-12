package tracker.application.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @param statusCategory new | indeterminate | done
 * @param created        YYYY-MM-DD
 * @param updated        ISO datetime
 * @param fields         raw values of mapped fields (JSON values: String, Number, Boolean, Map, List or null), by Jira field id
 */
public record IssueSnapshot(String key, String project, String summary, String statusName, String statusCategory,
                            UserSnapshot assignee, String parentKey, String created, String updated,
                            Map<String, Object> fields, List<WorklogSnapshot> worklogs, List<ChangeSnapshot> changes) {

    public IssueSnapshot {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        worklogs = List.copyOf(worklogs);
        changes = List.copyOf(changes);
    }
}
