package tracker.application;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tracker.application.AppError.Invalid;
import tracker.application.dto.JiraRequest;
import tracker.application.settings.TrackerSettings.FieldMapping;
import tracker.domain.TicketField;

/**
 * Builds the requests that change Jira, from the field mapping and Jira account ids. A review shows these requests and
 * apply sends the same ones, so nothing is written that the review did not show.
 */
public final class JiraRequests {
    private JiraRequests() {
    }

    public static final String ISSUE = "/rest/api/3/issue";
    /** Jira's own estimate field: written through time tracking, not by its id. */
    static final String ORIGINAL_ESTIMATE = "timeoriginalestimate";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The Jira field a ticket field is written to, as a review shows it. */
    public static String jiraField(TicketField f, FieldMapping fm) {
        return switch (f) {
            case STATUS -> "status";
            case ASSIGNEE -> "assignee";
            case REPORTER -> "reporter";
            case PLANNED_START -> fm.plannedStart;
            case PLANNED_END -> fm.plannedEnd;
            case ESTIMATE -> ORIGINAL_ESTIMATE.equals(fm.effort) ? "timetracking.originalEstimate" : fm.effort;
        };
    }

    /**
     * One PUT holding exactly these fields; empty when there are none. Values: a Jira account id for people, YYYY-MM-DD
     * for dates, hours (a Number) for the estimate; null clears the field. A status goes through {@link #transition}.
     */
    public static Optional<JiraRequest> update(String key, Map<TicketField, @Nullable Object> values, FieldMapping fm) {
        ObjectNode fields = JSON.createObjectNode();
        Map<TicketField, @Nullable Object> ordered = new EnumMap<>(TicketField.class);
        ordered.putAll(values);
        ordered.forEach((f, v) -> put(fields, f, v, fm));
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("fields", fields);
        return Optional.of(new JiraRequest("PUT", ISSUE + "/" + key, body.toString()));
    }

    public static JiraRequest transition(String key, String transitionId) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("transition").put("id", transitionId);
        return new JiraRequest("POST", ISSUE + "/" + key + "/transitions", body.toString());
    }

    /** A new sub-task under {@code parentKey}: summary, assignee, reporter left to Jira, dates and estimate when given. */
    public static JiraRequest createSubtask(String project, String issueTypeId, String parentKey, String summary, String assigneeAccountId,
                                            @Nullable String plannedStart, @Nullable String plannedEnd, double estimateH, FieldMapping fm) {
        Map<TicketField, @Nullable Object> values = new EnumMap<>(TicketField.class);
        values.put(TicketField.ASSIGNEE, assigneeAccountId);
        if (plannedStart != null) {
            values.put(TicketField.PLANNED_START, plannedStart);
        }
        if (plannedEnd != null) {
            values.put(TicketField.PLANNED_END, plannedEnd);
        }
        if (estimateH > 0) {
            values.put(TicketField.ESTIMATE, estimateH);
        }
        return createIssue(project, issueTypeId, parentKey, summary, values, fm);
    }

    /**
     * A new issue of the project with the given parent (an epic for a Task, a Task for a sub-task) and summary, plus exactly
     * {@code values} (as for {@link #update}; a status cannot be set on a new issue).
     */
    public static JiraRequest createIssue(String project, String issueTypeId, String parentKey, String summary,
                                          Map<TicketField, @Nullable Object> values, FieldMapping fm) {
        ObjectNode fields = JSON.createObjectNode();
        fields.putObject("project").put("key", project);
        fields.putObject("issuetype").put("id", issueTypeId);
        fields.putObject("parent").put("key", parentKey);
        fields.put("summary", summary);
        Map<TicketField, @Nullable Object> ordered = new EnumMap<>(TicketField.class);
        ordered.putAll(values);
        ordered.forEach((f, v) -> put(fields, f, v, fm));
        ObjectNode body = JSON.createObjectNode();
        body.set("fields", fields);
        return new JiraRequest("POST", ISSUE, body.toString());
    }

    private static void put(ObjectNode fields, TicketField f, @Nullable Object v, FieldMapping fm) {
        switch (f) {
            case STATUS -> throw new IllegalArgumentException("A status is changed through a transition");
            case ASSIGNEE, REPORTER -> {
                if (v == null) {
                    fields.putNull(f.value());
                } else {
                    fields.putObject(f.value()).put("accountId", (String) v);
                }
            }
            case PLANNED_START, PLANNED_END -> fields.put(jiraField(f, fm), (String) v);
            case ESTIMATE -> estimate(fields, v == null ? null : ((Number) v).doubleValue(), fm);
        }
    }

    private static void estimate(ObjectNode fields, @Nullable Double hours, FieldMapping fm) {
        if (ORIGINAL_ESTIMATE.equals(fm.effort)) {
            fields.putObject("timetracking").put("originalEstimate", hours == null ? null : Math.round(hours * 60) + "m");
        } else if (JiraValues.SECONDS_FIELDS.contains(fm.effort)) {
            throw new Invalid("The estimate cannot be written to " + fm.effort + "; map Planned Effort to " + ORIGINAL_ESTIMATE
                    + " or a number field", "estimate_h");
        } else if (hours == null) {
            fields.putNull(fm.effort);
        } else {
            fields.put(fm.effort, hours);
        }
    }
}
