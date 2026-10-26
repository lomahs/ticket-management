package tracker.application.ports;

import tracker.application.dto.JiraRequest;
import tracker.application.dto.JiraResponse;

/**
 * Writes to a Jira site. It only sends requests the application built ({@link tracker.application.JiraRequests}), so
 * what a review shows is what is sent.
 */
public interface JiraWriter {
    /** @throws JiraRejected when Jira refuses the request; {@link JiraError} when it cannot be reached */
    JiraResponse send(JiraRequest request);
}
