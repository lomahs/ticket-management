package tracker.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.adapters.SyncScheduler;
import tracker.adapters.api.ApiTestSupport;

/** Jira mode without configuration: read-only, and sync problems are reported. */
class JiraModeTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    void jiraModeWithoutConfigurationIsReadOnlyAndReportsSyncProblems() throws Exception {
        System.setProperty("tracker.home", tmp.resolve("backend-java").toString()); // the key file goes to <home>/data
        Bootstrap.App app = Bootstrap.buildJira("sqlite:///" + tmp.resolve("tracker.db"), null);
        try (SyncScheduler scheduler = SyncScheduler.start(app.deps())) {
            MockMvc c = ApiTestSupport.mvc(app.deps());
            JsonNode m = JSON.readTree(c.perform(MockMvcRequestBuilders.get("/api/v1/meta")).andReturn().getResponse().getContentAsString());
            assertThat(m.get("mode").asText()).isEqualTo("jira");
            assertThat(m.get("capabilities").toString()).isEqualTo("{\"jira_write\":false}");
            assertThat(m.get("sync").get("status").isNull()).isTrue();
            var sync = c.perform(MockMvcRequestBuilders.post("/api/v1/sync").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"full\": true}")).andReturn().getResponse();
            assertThat(JSON.readTree(sync.getContentAsString()).get("status").asText()).isEqualTo("ok"); // nothing tracked yet
            var epic = c.perform(MockMvcRequestBuilders.post("/api/v1/settings/epics").contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("key", "PAY-1")))).andReturn().getResponse();
            assertThat(epic.getStatus()).isEqualTo(502);
            assertThat(JSON.readTree(epic.getContentAsString()).get("code").asText()).isEqualTo("jira_unavailable");
            var jira = c.perform(MockMvcRequestBuilders.get("/api/v1/settings/jira")).andReturn().getResponse();
            assertThat(JSON.readTree(jira.getContentAsString()).get("status").isNull()).isTrue();
        } finally {
            app.db().close();
            System.clearProperty("tracker.home");
        }
        assertThat(tmp.resolve("backend-java").resolve("data").resolve("secret.key")).exists();
    }

    @Test
    void demoModeBootsWithSyncedData() {
        Bootstrap.App app = Bootstrap.buildDemo(tmp.resolve("demo").resolve("tracker.db"), "2026-10-07");
        try {
            assertThat(app.deps().mode()).isEqualTo("demo");
            assertThat(app.deps().writer()).isNotNull();
            assertThat(tracker.application.usecases.AnkenUseCases.overview(app.deps())).hasSize(6);
        } finally {
            app.db().close();
        }
    }
}
