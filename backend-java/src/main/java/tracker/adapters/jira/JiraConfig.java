package tracker.adapters.jira;

import java.util.List;

/** @param fields mapped field ids (planned start/end, effort) */
public record JiraConfig(String url, String email, String token, List<String> fields) {

    public JiraConfig {
        fields = List.copyOf(fields);
    }
}
