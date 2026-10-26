package tracker.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tracker.adapters.jira.HttpTransport;
import tracker.adapters.jira.JiraCloudWriter;
import tracker.adapters.jira.JiraConfig;
import tracker.application.dto.JiraRequest;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraError;
import tracker.application.ports.JiraRejected;

class JiraWriterTest {
    static final JiraRequest PUT = new JiraRequest("PUT", "/rest/api/3/issue/PAY-1213", "{\"fields\":{\"customfield_10016\":\"2026-10-08\"}}");

    /** Answers with the queued responses and records what was sent. */
    static final class Canned implements HttpTransport {
        final Deque<Response> answers = new ArrayDeque<>();
        final List<String> sent = new ArrayList<>();

        Canned answer(int status, String body) {
            answers.add(new Response(status, Map.of("Retry-After", "0"), body));
            return this;
        }

        @Override
        public Response send(String method, java.net.URI uri, Map<String, String> headers, String body) {
            sent.add(method + " " + uri + " " + headers.get("Content-Type") + " " + body);
            return answers.remove();
        }
    }

    static JiraCloudWriter writer(Canned t) {
        return new JiraCloudWriter(() -> new JiraConfig("https://acme.atlassian.net/", "bot@acme.vn", "token", List.of()), s -> { }, t);
    }

    @Test
    void sendsTheRequestAsItIs() {
        Canned t = new Canned().answer(204, "");
        assertThat(writer(t).send(PUT).status()).isEqualTo(204);
        assertThat(t.sent).containsExactly("PUT https://acme.atlassian.net/rest/api/3/issue/PAY-1213 application/json " + PUT.body());
    }

    @Test
    void refusalsCarryJirasMessages() {
        Canned t = new Canned().answer(400, "{\"errorMessages\":[],\"errors\":{\"customfield_10016\":\"Field 'customfield_10016' cannot be set.\"}}");
        assertThatThrownBy(() -> writer(t).send(PUT)).isInstanceOf(JiraRejected.class)
                .hasMessage("400 Bad Request: customfield_10016: Field 'customfield_10016' cannot be set.");
        assertThatThrownBy(() -> writer(new Canned().answer(401, "")).send(PUT)).isInstanceOf(JiraAuthError.class);
    }

    /** A write is retried after 429 only: after a server error Jira may have applied it. */
    @Test
    void retriesOnlyWhenThrottled() {
        Canned throttled = new Canned().answer(429, "").answer(204, "");
        assertThat(writer(throttled).send(PUT).status()).isEqualTo(204);
        assertThat(throttled.sent).hasSize(2);
        Canned failing = new Canned().answer(502, "").answer(204, "");
        assertThatThrownBy(() -> writer(failing).send(PUT)).isInstanceOf(JiraError.class).isNotInstanceOf(JiraRejected.class)
                .hasMessageContaining("may or may not have been applied");
        assertThat(failing.sent).hasSize(1);
    }
}
