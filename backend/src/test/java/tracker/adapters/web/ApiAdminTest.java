package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;
import tracker.application.service.AccessService;
import tracker.domain.Identity;

@DemoTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiAdminTest extends ApiTestSupport {
    @Autowired
    AccessService accessService;

    static JsonNode row(JsonNode rows, String id) {
        for (JsonNode r : rows) {
            if (r.get("member").get("id").asText().equals(id)) {
                return r;
            }
        }
        throw new AssertionError("no row " + id);
    }

    @Test
    void adminListsUsersWithEffectivePermissions() throws Exception {
        JsonNode rows = call(get("/api/v1/admin/users")).json();
        JsonNode an = row(rows, "an");
        assertThat(an.get("access").asText()).isEqualTo("active");
        assertThat(an.get("effective").get("matrix").asText()).isEqualTo("read");
        assertThat(row(rows, "tanaka").get("access").asText()).isEqualTo("none");
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        problem(call(get("/api/v1/admin/users").with(as("an.nguyen@acme.vn"))), 403, "forbidden");
    }

    @Test
    void approveFlowOverHttp() throws Exception {
        accessService.requestAccess(new Identity("lan.vo@acme.vn", "Võ Thị Lan"), "");
        JsonNode pending = call(get("/api/v1/admin/access-requests").param("status", "pending")).json();
        assertThat(pending).hasSize(1);
        int id = pending.get(0).get("id").asInt();
        Res ok = call(json(post("/api/v1/admin/access-requests/" + id + "/approve"), Map.of("role", "PIM")));
        assertThat(ok.status()).as(ok.text()).isEqualTo(200);
        assertThat(ok.json().get("member").get("role").asText()).isEqualTo("PIM");
        assertThat(ok.json().get("access").asText()).isEqualTo("active");
        problem(call(json(post("/api/v1/admin/access-requests/" + id + "/approve"), Map.of("role", "PIM"))), 409, "conflict");
    }

    @Test
    void unknownStatusFilterIs400() throws Exception {
        problem(call(get("/api/v1/admin/access-requests").param("status", "bogus")), 400, "invalid");
        problem(call(get("/api/v1/admin/access-requests").param("status", "")), 400, "invalid");
    }

    @Test
    void rejectOverHttp() throws Exception {
        accessService.requestAccess(new Identity("lan.vo@acme.vn", "Võ Thị Lan"), "");
        int id = call(get("/api/v1/admin/access-requests")).json().get(0).get("id").asInt();
        Res r = call(post("/api/v1/admin/access-requests/" + id + "/reject"));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("rejected");
    }

    @Test
    void patchOverridesAndReset() throws Exception {
        JsonNode a = call(json(patch("/api/v1/admin/users/an"), Map.of("overrides", Map.of("planning", "write")))).json();
        assertThat(a.get("effective").get("planning").asText()).isEqualTo("write");
        assertThat(a.get("overrides").get("planning").asText()).isEqualTo("write");
        Map<String, Object> reset = new HashMap<>();
        reset.put("planning", null);
        JsonNode b = call(json(patch("/api/v1/admin/users/an"), Map.of("overrides", reset))).json();
        assertThat(b.get("overrides")).isEmpty();
    }

    @Test
    void selfLockoutIs422() throws Exception {
        problem(call(json(patch("/api/v1/admin/users/minh"), Map.of("admin", false))), 422, "invalid");
    }

    @Test
    void patchAnkenAndScope() throws Exception {
        Res r = call(json(patch("/api/v1/admin/users/an"), Map.of("anken", java.util.List.of("PAY-1300"), "anken_scope", "all")));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode an = r.json();
        assertThat(an.get("anken")).hasSize(1);
        assertThat(an.get("anken").get(0).asText()).isEqualTo("PAY-1300");
        assertThat(an.get("anken_scope").asText()).isEqualTo("all");
        assertThat(an.get("all_anken").asBoolean()).isTrue();
    }

    @Test
    void mergeOverHttp() throws Exception {
        Res created = call(json(post("/api/v1/people"), Map.of("email", "an.second@acme.vn", "name", "An 2", "epics", java.util.List.of("PAY-1100"))));
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        String dup = created.json().get("id").asText();
        Res ok = call(json(post("/api/v1/admin/members/merge"), Map.of("keep_id", "an", "remove_id", dup)));
        assertThat(ok.status()).as(ok.text()).isEqualTo(200);
        assertThat(ok.json().get("member").get("id").asText()).isEqualTo("an");
        JsonNode rows = call(get("/api/v1/admin/users")).json();
        for (JsonNode r : rows) {
            assertThat(r.get("member").get("id").asText()).isNotEqualTo(dup);
        }
        problem(call(json(post("/api/v1/admin/members/merge"), Map.of("keep_id", "an", "remove_id", dup))), 404, "not_found");
    }

    @Test
    void twoJiraAccountsAreNotMergedOverHttp() throws Exception {
        Res r = call(json(post("/api/v1/admin/members/merge"), Map.of("keep_id", "an", "remove_id", "tanaka")));
        problem(r, 422, "invalid");
        assertThat(r.text()).contains("Both members have a Jira account; they cannot be merged").contains("remove_id");
        assertThat(row(call(get("/api/v1/admin/users")).json(), "tanaka").get("member").get("id").asText()).isEqualTo("tanaka");
    }
}
