package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.TrackerTest;

/** Without the demo profile there is no JiraWriter: Jira edits are refused (phase 1). */
@TrackerTest
@AutoConfigureMockMvc
class ApiJiraModeTest extends ApiTestSupport {

    @Test
    void writesToJiraAreDisabled() throws Exception {
        JsonNode meta = call(get("/api/v1/meta")).json();
        assertThat(meta.get("mode").asText()).isEqualTo("jira");
        assertThat(meta.get("capabilities").toString()).isEqualTo("{\"jira_write\":false}");
        problem(call(json(patch("/api/v1/tickets/PAY-1213"), Map.of("status", "R"))), 403, "capability_disabled");
        problem(call(json(post("/api/v1/planning/push"), Map.of("changes", List.of()))), 403, "capability_disabled");
    }

    @Test
    void withoutAJiraSiteSyncsAreEmptyAndEpicsCannotBeChecked() throws Exception {
        assertThat(call(get("/api/v1/meta")).json().get("sync").get("status").isNull()).isTrue();
        assertThat(call(json(post("/api/v1/sync"), Map.of("full", true))).json().get("status").asText()).isEqualTo("ok"); // nothing tracked
        problem(call(json(post("/api/v1/settings/epics"), Map.of("key", "PAY-1"))), 502, "jira_unavailable");
        assertThat(call(get("/api/v1/settings/jira")).json().get("status").isNull()).isTrue();
    }

    @Test
    void anEmptyDatabaseServesEmptyScreens() throws Exception {
        assertThat(call(get("/api/v1/anken")).text()).isEqualTo("[]");
        assertThat(call(get("/api/v1/members")).text()).isEqualTo("[]");
        assertThat(call(get("/api/v1/settings/jira")).json().get("interval").asText()).isEqualTo("15");
    }
}
