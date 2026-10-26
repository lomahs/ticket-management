package tracker.adapters.jira;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.dto.JiraRequest;
import tracker.application.dto.JiraResponse;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraError;
import tracker.application.ports.JiraRejected;
import tracker.application.ports.JiraWriter;

/**
 * Sends the app's change requests to Jira Cloud (REST v3) with the stored account and API token. A write is not repeated
 * after a server error, since Jira may have applied it; only "429 Too Many Requests" is retried, after the wait it asks for.
 */
public final class JiraCloudWriter implements JiraWriter {
    static final int MAX_ATTEMPTS = 4;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<JiraConfig> config;
    private final DoubleConsumer sleep;
    private final HttpTransport transport;

    public JiraCloudWriter(Supplier<JiraConfig> config) {
        this(config, JiraCloudWriter::sleepSeconds, new JdkHttpTransport(Duration.ofSeconds(20)));
    }

    public JiraCloudWriter(Supplier<JiraConfig> config, DoubleConsumer sleep, HttpTransport transport) {
        this.config = config;
        this.sleep = sleep;
        this.transport = transport;
    }

    private static void sleepSeconds(double s) {
        try {
            Thread.sleep((long) (s * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JiraError("interrupted");
        }
    }

    @Override
    public JiraResponse send(JiraRequest request) {
        JiraConfig cfg = config.get();
        if (cfg.url() == null || cfg.url().isEmpty()) {
            throw new JiraError("Jira site URL is not configured");
        }
        URI uri = URI.create(cfg.url().replaceAll("/+$", "") + request.path());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((cfg.email() + ":" + cfg.token()).getBytes(StandardCharsets.UTF_8)));
        if (request.body() != null) {
            headers.put("Content-Type", "application/json");
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpTransport.Response r;
            try {
                r = transport.send(request.method(), uri, headers, request.body());
            } catch (IOException | RuntimeException e) {
                throw new JiraError("Cannot reach Jira: " + e.getMessage(), e);
            }
            int status = r.status();
            String text = r.body() == null ? "" : r.body();
            if (status == 429 && attempt < MAX_ATTEMPTS - 1) {
                String retryAfter = r.header("Retry-After");
                sleep.accept(retryAfter != null && retryAfter.replace(".", "").matches("\\d+") ? Double.parseDouble(retryAfter) : Math.pow(2, attempt));
                continue;
            }
            if (status == 401) {
                throw new JiraAuthError("401 Unauthorized: check the account email and API token");
            }
            if (status >= 500 || status == 429) {
                throw new JiraError(status + " " + HttpReasons.phrase(status) + " from Jira; the change may or may not have been applied");
            }
            if (status >= 400) {
                throw new JiraRejected(status, status + " " + HttpReasons.phrase(status) + ": " + messages(text));
            }
            return new JiraResponse(status, text);
        }
        throw new JiraError("unreachable");
    }

    /** Jira's {@code errorMessages} and per-field {@code errors}, joined; the raw text when it is not JSON. */
    static String messages(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            List<String> out = new ArrayList<>();
            n.path("errorMessages").forEach(m -> out.add(m.asText()));
            n.path("errors").fields().forEachRemaining(e -> out.add(e.getKey() + ": " + e.getValue().asText()));
            if (!out.isEmpty()) {
                return String.join("; ", out);
            }
        } catch (IOException | RuntimeException e) {
            // not JSON: fall through to the raw text
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
