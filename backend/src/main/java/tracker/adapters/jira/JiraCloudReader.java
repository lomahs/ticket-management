package tracker.adapters.jira;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tracker.application.dto.ChangeSnapshot;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.UserSnapshot;
import tracker.application.dto.WorklogSnapshot;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraError;
import tracker.application.ports.JiraReader;

/**
 * Jira Cloud REST API v3 reader.
 *
 * <p>Structure read: tracked epic → parent tickets (one per phase) → sub-tasks. Search uses the enhanced JQL endpoint
 * (POST /rest/api/3/search/jql, nextPageToken pagination). Worklogs and changelogs embedded in search results can be
 * truncated; those are completed from the per-issue endpoints.
 */
public final class JiraCloudReader implements JiraReader {
    static final List<String> BASE_FIELDS = List.of("summary", "status", "assignee", "reporter", "parent", "project", "created", "updated", "worklog", "labels");
    static final int MAX_ATTEMPTS = 5;
    static final int PARENT_CHUNK = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter JIRA_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH", "Z").optionalEnd()
            .toFormatter();

    private final Supplier<JiraConfig> config;
    private final DoubleConsumer sleep;
    private final HttpTransport transport;

    public JiraCloudReader(Supplier<JiraConfig> config) {
        this(config, JiraCloudReader::sleepSeconds, new JdkHttpTransport(Duration.ofSeconds(20)));
    }

    public JiraCloudReader(Supplier<JiraConfig> config, DoubleConsumer sleep, HttpTransport transport) {
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

    // --- HTTP -------------------------------------------------------------------------------------------------------

    private record Client(String base, Map<String, String> headers, JiraConfig cfg) {
    }

    private Client client() {
        JiraConfig cfg = config.get();
        if (cfg.url() == null || cfg.url().isEmpty()) {
            throw new JiraError("Jira site URL is not configured");
        }
        String base = cfg.url().replaceAll("/+$", "");
        String auth = Base64.getEncoder().encodeToString((cfg.email() + ":" + cfg.token()).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Basic " + auth);
        return new Client(base, headers, cfg);
    }

    private static String query(Map<String, Object> params) {
        if (params.isEmpty()) {
            return "";
        }
        return "?" + params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** The parsed JSON body; null on 404. */
    private JsonNode call(Client client, String method, String path, Map<String, Object> params, JsonNode body) {
        URI uri = URI.create(client.base() + path + query(params));
        Map<String, String> headers = new LinkedHashMap<>(client.headers());
        String payload = null;
        if (body != null) {
            headers.put("Content-Type", "application/json");
            payload = body.toString();
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpTransport.Response r;
            try {
                r = transport.send(method, uri, headers, payload);
            } catch (IOException | RuntimeException e) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw new JiraError("Cannot reach Jira: " + e.getMessage(), e);
                }
                sleep.accept(Math.pow(2, attempt));
                continue;
            }
            int status = r.status();
            if (status == 401 || status == 403) {
                throw new JiraAuthError(status + " " + HttpReasons.phrase(status) + ": check the account email and API token");
            }
            if (status == 404) {
                return null;
            }
            if (status == 429 || status >= 500) {
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw new JiraError(status + " " + HttpReasons.phrase(status) + " from Jira after " + MAX_ATTEMPTS + " attempts");
                }
                String retryAfter = r.header("Retry-After");
                sleep.accept(retryAfter != null && retryAfter.replace(".", "").matches("\\d+")
                        ? Double.parseDouble(retryAfter) : Math.pow(2, attempt));
                continue;
            }
            String text = r.body() == null ? "" : r.body();
            if (status >= 400) {
                throw new JiraError(status + " " + HttpReasons.phrase(status) + ": " + text.substring(0, Math.min(300, text.length())));
            }
            try {
                return JSON.readTree(text);
            } catch (IOException e) {
                throw new JiraError("Jira returned invalid JSON: " + e.getMessage(), e);
            }
        }
        throw new JiraError("unreachable");
    }

    private static JsonNode obj(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? JSON.createObjectNode() : n;
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    private static String textOrNull(JsonNode n, String field) {
        return text(n, field, null);
    }

    // --- JiraReader -------------------------------------------------------------------------------------------------

    private List<JsonNode> search(Client client, String jql) {
        List<JsonNode> out = new ArrayList<>();
        String token = null;
        while (true) {
            ObjectNode body = JSON.createObjectNode();
            body.put("jql", jql);
            ArrayNode fields = body.putArray("fields");
            BASE_FIELDS.forEach(fields::add);
            client.cfg().fields().forEach(fields::add);
            body.put("expand", "changelog");
            body.put("maxResults", 100);
            if (token != null && !token.isEmpty()) {
                body.put("nextPageToken", token);
            }
            JsonNode page = obj(call(client, "POST", "/rest/api/3/search/jql", Map.of(), body));
            page.path("issues").forEach(out::add);
            token = textOrNull(page, "nextPageToken");
            JsonNode isLast = page.get("isLast");
            boolean last = isLast == null || isLast.isNull() || isLast.asBoolean();
            if (last || token == null || token.isEmpty()) {
                return out;
            }
        }
    }

    private static String quote(List<String> keys) {
        return keys.stream().map(k -> "\"" + k + "\"").collect(Collectors.joining(", "));
    }

    static Instant parseTime(String s) {
        return OffsetDateTime.parse(s, JIRA_TIME.withZone(ZoneOffset.UTC)).toInstant();
    }

    @Override
    public List<IssueSnapshot> search(List<String> epicKeys, @Nullable Instant updatedSince) {
        Client client = client();
        List<IssueSnapshot> out = new ArrayList<>();
        List<JsonNode> parents = search(client, "parent in (" + quote(epicKeys) + ") ORDER BY key");
        for (JsonNode p : parents) {
            if (updatedSince == null || !parseTime(p.path("fields").path("updated").asText()).isBefore(updatedSince)) {
                out.add(snapshot(client, p));
            }
        }
        List<String> keys = parents.stream().map(p -> p.path("key").asText()).toList();
        String recent = "";
        if (updatedSince != null) {
            double seconds = Duration.between(updatedSince, Instant.now()).toNanos() / 1e9;
            long minutes = (long) Math.ceil(seconds / 60);
            recent = " AND updated >= \"-" + Math.max(1, minutes) + "m\""; // relative: independent of the account's time zone
        }
        for (int i = 0; i < keys.size(); i += PARENT_CHUNK) {
            String jql = "parent in (" + quote(keys.subList(i, Math.min(keys.size(), i + PARENT_CHUNK))) + ")" + recent + " ORDER BY key";
            for (JsonNode issue : search(client, jql)) {
                out.add(snapshot(client, issue));
            }
        }
        return out;
    }

    @Override
    public Optional<EpicSnapshot> findEpic(String key) {
        Client client = client();
        JsonNode data = call(client, "GET", "/rest/api/3/issue/" + key, Map.of("fields", "summary,issuetype,project,duedate"), null);
        if (data == null || data.isNull()) {
            return Optional.empty();
        }
        JsonNode f = data.path("fields");
        JsonNode it = obj(f.get("issuetype"));
        boolean epic = "Epic".equals(textOrNull(it, "name")) || (it.has("hierarchyLevel") && it.get("hierarchyLevel").isNumber()
                && it.get("hierarchyLevel").asInt() == 1);
        if (!epic) {
            return Optional.empty();
        }
        return Optional.of(new EpicSnapshot(data.path("key").asText(), f.path("project").path("key").asText(), text(f, "summary", ""),
                textOrNull(f, "duedate")));
    }

    @Override
    public Optional<IssueSnapshot> findIssue(String key) {
        Client client = client();
        List<String> fields = new ArrayList<>(BASE_FIELDS);
        fields.addAll(client.cfg().fields());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fields", String.join(",", fields));
        params.put("expand", "changelog");
        JsonNode data = call(client, "GET", "/rest/api/3/issue/" + key, params, null);
        return data != null && !data.isNull() ? Optional.of(snapshot(client, data)) : Optional.empty();
    }

    @Override
    public List<JiraField> listFields() {
        Client client = client();
        JsonNode data = call(client, "GET", "/rest/api/3/field", Map.of(), null);
        List<JiraField> out = new ArrayList<>();
        if (data != null) {
            for (JsonNode f : data) {
                String id = f.path("id").asText();
                out.add(new JiraField(id, text(f, "name", id), text(obj(f.get("schema")), "type", "")));
            }
        }
        return out;
    }

    @Override
    public boolean testConnection() {
        try {
            Client client = client();
            JsonNode me = call(client, "GET", "/rest/api/3/myself", Map.of(), null);
            return me != null;
        } catch (JiraError e) {
            return false;
        }
    }

    // --- mapping ----------------------------------------------------------------------------------------------------

    /** Plain text of an Atlassian Document Format value (or a plain string). */
    static String adfText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if ("text".equals(textOrNull(node, "type"))) {
            return text(node, "text", "");
        }
        List<String> parts = new ArrayList<>();
        node.path("content").forEach(c -> {
            String p = adfText(c);
            if (!p.isEmpty()) {
                parts.add(p);
            }
        });
        return String.join("doc".equals(textOrNull(node, "type")) ? "\n" : "", parts);
    }

    private static UserSnapshot account(JsonNode u) {
        return new UserSnapshot(u.path("accountId").asText(), text(u, "displayName", ""), text(u, "emailAddress", ""));
    }

    private static UserSnapshot user(JsonNode u) {
        return u == null || u.isNull() || u.isMissingNode() || u.isEmpty() ? null : account(u);
    }

    private List<JsonNode> worklogs(Client client, String key, JsonNode embedded) {
        List<JsonNode> logs = new ArrayList<>();
        embedded.path("worklogs").forEach(logs::add);
        if (!embedded.has("total") || embedded.get("total").asInt() <= logs.size()) {
            return logs;
        }
        List<JsonNode> out = new ArrayList<>();
        while (true) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("startAt", out.size());
            params.put("maxResults", 5000);
            JsonNode page = obj(call(client, "GET", "/rest/api/3/issue/" + key + "/worklog", params, null));
            page.path("worklogs").forEach(out::add);
            if (out.size() >= page.path("total").asInt(0) || page.path("worklogs").isEmpty()) {
                return out;
            }
        }
    }

    private List<JsonNode> histories(Client client, String key, JsonNode embedded) {
        List<JsonNode> hist = new ArrayList<>();
        embedded.path("histories").forEach(hist::add);
        if (!embedded.has("total") || embedded.get("total").asInt() <= hist.size()) {
            return hist;
        }
        List<JsonNode> out = new ArrayList<>();
        while (true) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("startAt", out.size());
            params.put("maxResults", 100);
            JsonNode page = obj(call(client, "GET", "/rest/api/3/issue/" + key + "/changelog", params, null));
            page.path("values").forEach(out::add);
            JsonNode isLast = page.get("isLast");
            boolean last = isLast == null || isLast.isNull() || isLast.asBoolean();
            if (last || page.path("values").isEmpty()) {
                return out;
            }
        }
    }

    private static String prefix(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** A JSON value as a plain Java value: String, Long/Integer/Double, Boolean, Map, List or null. */
    private static Object plain(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        return JSON.convertValue(v, Object.class);
    }

    private static List<String> labels(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(l -> out.add(l.asText()));
        }
        return out;
    }

    private IssueSnapshot snapshot(Client client, JsonNode issue) {
        String key = issue.path("key").asText();
        JsonNode f = issue.path("fields");
        List<WorklogSnapshot> logs = new ArrayList<>();
        for (JsonNode w : worklogs(client, key, obj(f.get("worklog")))) {
            logs.add(new WorklogSnapshot(account(w.path("author")), prefix(w.path("started").asText(), 10),
                    w.path("timeSpentSeconds").asDouble(0) / 3600, adfText(w.get("comment"))));
        }
        List<ChangeSnapshot> changes = new ArrayList<>();
        for (JsonNode h : histories(client, key, obj(issue.get("changelog")))) {
            String at = prefix(h.path("created").asText(), 10);
            for (JsonNode it : h.path("items")) {
                String field = textOrNull(it, "field");
                if ("assignee".equals(field) || "reporter".equals(field)) {
                    String from = textOrNull(it, "from");
                    String to = textOrNull(it, "to");
                    UserSnapshot fromUser = from != null && !from.isEmpty() ? new UserSnapshot(from, text(it, "fromString", "")) : null;
                    UserSnapshot toUser = to != null && !to.isEmpty() ? new UserSnapshot(to, text(it, "toString", "")) : null;
                    changes.add(new ChangeSnapshot(at, field, from, to, fromUser, toUser));
                } else if ("status".equals(field)) {
                    changes.add(new ChangeSnapshot(at, "status", textOrNull(it, "fromString"), textOrNull(it, "toString")));
                }
            }
        }
        JsonNode status = obj(f.get("status"));
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String fid : client.cfg().fields()) {
            fields.put(fid, plain(f.get(fid)));
        }
        String updated = f.hasNonNull("updated") && !f.get("updated").asText().isEmpty()
                ? parseTime(f.get("updated").asText()).toString() : "";
        return new IssueSnapshot(
                key, text(obj(f.get("project")), "key", key.split("-", -1)[0]), text(f, "summary", ""),
                text(status, "name", ""), text(obj(status.get("statusCategory")), "key", "indeterminate"),
                user(f.get("assignee")), textOrNull(obj(f.get("parent")), "key"),
                prefix(text(f, "created", ""), 10), updated, fields, logs, changes, user(f.get("reporter")), labels(f.get("labels")));
    }
}
