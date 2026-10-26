package tracker.application.dto;

import org.jspecify.annotations.Nullable;

/**
 * One call to Jira's REST API that changes it, exactly as it is shown in a review, sent and logged.
 *
 * @param path from the site root, e.g. /rest/api/3/issue/PAY-1213
 * @param body JSON, or null
 */
public record JiraRequest(String method, String path, @Nullable String body) {
}
