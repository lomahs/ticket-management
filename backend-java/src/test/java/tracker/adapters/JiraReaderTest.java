package tracker.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.adapters.jira.HttpTransport;
import tracker.adapters.jira.JiraCloudReader;
import tracker.adapters.jira.JiraConfig;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.ports.JiraAuthError;
import tracker.application.ports.JiraError;

/** The Jira Cloud reader against recorded REST v3 responses. */
class JiraReaderTest {
    static final String SITE = "https://acme-pay.atlassian.net";
    static final JiraConfig CFG = new JiraConfig(SITE, "bot@acme.vn", "token",
            List.of("customfield_10020", "customfield_10016", "timeoriginalestimate"));
    static final ObjectMapper JSON = new ObjectMapper();

    static String fx(String name) {
        try (var in = JiraReaderTest.class.getResourceAsStream("/jira/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record Call(String method, URI uri, Map<String, String> headers, String body) {
    }

    /** Routes requests by "METHOD path" to responses; records every call. */
    static final class FakeSite implements HttpTransport {
        final Map<String, Function<Call, Response>> routes = new LinkedHashMap<>();
        final List<Call> calls = new ArrayList<>();

        FakeSite on(String method, String path, Function<Call, Response> r) {
            routes.put(method + " " + path, r);
            return this;
        }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body) {
            Call c = new Call(method, uri, headers, body);
            calls.add(c);
            Function<Call, Response> r = routes.get(method + " " + uri.getPath());
            return r != null ? r.apply(c) : new Response(404, Map.of(), "");
        }
    }

    static HttpTransport.Response ok(String body) {
        return new HttpTransport.Response(200, Map.of(), body);
    }

    static HttpTransport.Response status(int code) {
        return new HttpTransport.Response(code, Map.of(), "");
    }

    static JiraCloudReader reader(FakeSite site, List<Double> sleeps) {
        return new JiraCloudReader(() -> CFG, sleeps::add, site);
    }

    static FakeSite searchSite(boolean incremental) {
        return new FakeSite().on("POST", "/rest/api/3/search/jql", c -> {
            try {
                JsonNode body = JSON.readTree(c.body());
                String jql = body.get("jql").asText();
                assertThat(body.get("expand").asText()).isEqualTo("changelog");
                assertThat(body.get("fields").toString()).contains("customfield_10020", "worklog");
                if (jql.startsWith("parent in (\"PAY-1100\")")) {
                    return ok(fx("search_parents.json"));
                }
                assertThat(jql).startsWith("parent in (\"PAY-1102\")");
                if (incremental) {
                    assertThat(jql).contains("updated >= \"-");
                }
                boolean page2 = body.has("nextPageToken") && body.get("nextPageToken").asText().equals("page-2");
                return ok(fx(page2 ? "search_subtasks_page2.json" : "search_subtasks_page1.json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Test
    void searchWalksEpicParentsThenSubtasksAcrossPages() {
        FakeSite site = searchSite(false)
                .on("GET", "/rest/api/3/issue/PAY-1213/worklog", c -> ok(fx("worklog_PAY-1213.json")))
                .on("GET", "/rest/api/3/issue/PAY-1213/changelog", c -> ok(fx("changelog_PAY-1213.json")));
        Map<String, IssueSnapshot> snaps = new LinkedHashMap<>();
        reader(site, new ArrayList<>()).search(List.of("PAY-1100"), null).forEach(s -> snaps.put(s.key(), s));
        assertThat(snaps.keySet()).containsExactly("PAY-1102", "PAY-1201", "PAY-1213");
        IssueSnapshot p = snaps.get("PAY-1102"), c = snaps.get("PAY-1201"), f = snaps.get("PAY-1213");
        assertThat(List.of(p.parentKey(), p.project(), p.created(), p.assignee().displayName()))
                .containsExactly("PAY-1100", "PAY", "2026-09-10", "Hoàng Minh");
        assertThat(List.of(c.statusName(), c.statusCategory(), c.fields().get("customfield_10016"))).containsExactly("Done", "done", "2026-09-15");
        assertThat(((Number) c.fields().get("timeoriginalestimate")).intValue()).isEqualTo(36000);
        assertThat(c.worklogs()).extracting(w -> w.date() + "/" + w.hours() + "/" + w.comment())
                .containsExactly("2026-09-15/5.0/Write spec", "2026-09-14/5.0/");
        assertThat(c.changes()).extracting(x -> x.at() + "/" + x.field() + "/" + x.fromValue() + "/" + x.toValue())
                .containsExactly("2026-09-16/status/In Progress/Done");
        // truncated worklogs and changelog were fetched in full
        assertThat(f.worklogs()).extracting(w -> w.hours()).containsExactly(3.0, 2.5, 2.0);
        assertThat(f.worklogs().get(0).comment()).isEqualTo("plain text comment");
        assertThat(f.changes()).extracting(x -> x.at() + "/" + x.fromValue() + "/" + x.toValue() + "/" + x.toUser().displayName())
                .containsExactly("2026-09-30/5b10ac8d82e05b22cc7d4ef5/557058:binh/Trần Văn Bình",
                        "2026-10-05/557058:binh/5b10ac8d82e05b22cc7d4ef5/Hoàng Minh");
    }

    @Test
    void incrementalSearchFiltersByRelativeUpdatedTime() {
        FakeSite site = searchSite(true)
                .on("GET", "/rest/api/3/issue/PAY-1213/worklog", c -> ok(fx("worklog_PAY-1213.json")))
                .on("GET", "/rest/api/3/issue/PAY-1213/changelog", c -> ok(fx("changelog_PAY-1213.json")));
        List<String> keys = new ArrayList<>();
        reader(site, new ArrayList<>()).search(List.of("PAY-1100"), Instant.now().minus(Duration.ofDays(3))).forEach(s -> keys.add(s.key()));
        assertThat(keys).doesNotContain("PAY-1102").contains("PAY-1201"); // the parent was last updated before `since`
    }

    @Test
    void rateLimitIsRetriedAfterTheServerDelay() {
        int[] n = {0};
        FakeSite site = new FakeSite().on("GET", "/rest/api/3/myself", c -> n[0]++ == 0
                ? new HttpTransport.Response(429, Map.of("Retry-After", "2"), "") : ok("{\"accountId\": \"x\"}"));
        List<Double> sleeps = new ArrayList<>();
        assertThat(reader(site, sleeps).testConnection()).isTrue();
        assertThat(sleeps).containsExactly(2.0);
    }

    @Test
    void serverErrorsBackOffThenGiveUp() {
        FakeSite site = new FakeSite().on("GET", "/rest/api/3/issue/PAY-1", c -> status(503));
        List<Double> sleeps = new ArrayList<>();
        assertThatThrownBy(() -> reader(site, sleeps).findIssue("PAY-1")).isInstanceOf(JiraError.class)
                .hasMessage("503 Service Unavailable from Jira after 5 attempts");
        assertThat(sleeps).containsExactly(1.0, 2.0, 4.0, 8.0);
    }

    @Test
    void rejectedCredentialsRaiseAuthErrorAndFailConnectionTest() {
        FakeSite site = new FakeSite().on("POST", "/rest/api/3/search/jql", c -> status(401)).on("GET", "/rest/api/3/myself", c -> status(401));
        assertThatThrownBy(() -> reader(site, new ArrayList<>()).search(List.of("PAY-1100"), null)).isInstanceOf(JiraAuthError.class)
                .hasMessage("401 Unauthorized: check the account email and API token");
        assertThat(reader(site, new ArrayList<>()).testConnection()).isFalse();
    }

    @Test
    void findEpicOnlyReturnsEpics() {
        FakeSite site = new FakeSite().on("GET", "/rest/api/3/issue/PAY-1100", c -> ok(fx("epic_PAY-1100.json")))
                .on("GET", "/rest/api/3/issue/PAY-1201", c -> ok(fx("story_PAY-1201.json")));
        JiraCloudReader r = reader(site, new ArrayList<>());
        assertThat(r.findEpic("PAY-1100")).contains(new EpicSnapshot("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"));
        assertThat(r.findEpic("PAY-1201")).isEmpty();
        assertThat(r.findEpic("PAY-9")).isEmpty();
    }

    @Test
    void requestsUseBasicAuth() {
        FakeSite site = new FakeSite().on("GET", "/rest/api/3/myself", c -> ok("{}"));
        reader(site, new ArrayList<>()).testConnection();
        assertThat(site.calls.get(0).headers().get("Authorization")).startsWith("Basic ");
    }

    @Test
    void listFieldsReadsTheFieldCatalog() {
        FakeSite site = new FakeSite().on("GET", "/rest/api/3/field", c -> ok("""
                [{"id": "customfield_10020", "name": "Planned Start", "schema": {"type": "date"}},
                 {"id": "timeoriginalestimate", "name": "Original estimate", "schema": {"type": "number"}},
                 {"id": "summary", "name": "Summary", "schema": {"type": "string"}},
                 {"id": "issuekey", "name": "Key"}]"""));
        Map<String, JiraField> fields = new HashMap<>();
        reader(site, new ArrayList<>()).listFields().forEach(f -> fields.put(f.id(), f));
        assertThat(fields.get("customfield_10020")).isEqualTo(new JiraField("customfield_10020", "Planned Start", "date"));
        assertThat(fields.get("issuekey").type()).isEmpty();
    }

    @Test
    void missingSiteUrlIsAJiraError() {
        JiraCloudReader r = new JiraCloudReader(() -> new JiraConfig("", "", "", List.of()), s -> { }, new FakeSite());
        assertThatThrownBy(() -> r.findEpic("PAY-1")).isInstanceOf(JiraError.class).hasMessage("Jira site URL is not configured");
        assertThat(r.testConnection()).isFalse();
    }
}
