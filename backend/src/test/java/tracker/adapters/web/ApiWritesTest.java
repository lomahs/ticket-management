package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;

/** API calls that change data; each test starts from fresh demo data. */
@DemoTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiWritesTest extends ApiTestSupport {

    @Test
    void ticketPatchAndPlanningPush() throws Exception {
        assertThat(call(json(patch("/api/v1/tickets/PAY-1213"), Map.of("status", "R"))).json().get("card").get("status").asText())
                .isEqualTo("R");
        Res r = call(json(post("/api/v1/planning/push"),
                Map.of("changes", List.of(Map.of("key", "PAY-1262", "start", "2026-10-12", "member_id", "binh")))));
        assertThat(r.text()).isEqualTo("{\"keys\":[\"PAY-1262\"]}");
    }

    @Test
    void createFunction() throws Exception {
        Res r = call(json(post("/api/v1/anken/PAY-1100/functions"), Map.of("parent_key", "PAY-1102", "name", "Installment",
                "ba", "an", "bal", "minh", "jm", "tanaka", "create_hours", 8)));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("keys").get(0).asText()).isEqualTo("PAY-1501");
    }

    @Test
    void leavesCrudImportExport() throws Exception {
        Res r = call(json(post("/api/v1/leaves"), Map.of("member_id", "an", "type", "full", "date_from", "2026-11-02",
                "date_to", "2026-11-06", "note", "")));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text()).isEqualTo("{\"days\":5}");
        problem(call(json(post("/api/v1/leaves"), Map.of("member_id", "an", "type", "full", "date_from", "2026-11-06",
                "date_to", "2026-11-02"))), 422, "invalid");
        MockMultipartFile csv = new MockMultipartFile("file", "x.csv", "text/csv",
                "﻿Member,From,To,Type,Note\nChi,2026-12-01,2026-12-02,Half day,\n".getBytes(StandardCharsets.UTF_8));
        JsonNode pv = call(multipart("/api/v1/leaves/import/preview").file(csv)).json();
        assertThat(pv.get("file").asText()).isEqualTo("x.csv");
        assertThat(pv.get("rows").get(0).get("error").asText()).isEmpty();
        assertThat(pv.get("working_days").asInt()).isEqualTo(2);
        JsonNode sample = call(post("/api/v1/leaves/import/preview").param("sample", "true")).json();
        assertThat(sample.get("file").asText()).isEqualTo("leave-november.xlsx");
        assertThat(sample.get("rows")).hasSize(10);
        JsonNode res = call(json(post("/api/v1/leaves/import"), Map.of("rows", List.of(List.of("Chi", "2026-12-01", "2026-12-02",
                "Half day", ""))))).json();
        assertThat(res.toString()).isEqualTo("{\"imported\":2,\"month\":\"2026-12\"}");
        Res x = call(get("/api/v1/leaves/export").param("month", "2026-12"));
        assertThat(x.contentType()).startsWith("application/vnd.ms-excel");
        assertThat(x.text()).contains("Lê Minh Chi");
        assertThat(call(get("/api/v1/leaves/template")).text()).startsWith("﻿Member");
        problem(call(multipart("/api/v1/leaves/import/preview").file(new MockMultipartFile("file", "x.pdf", "application/pdf",
                "%PDF".getBytes()))), 422, "invalid");
        assertThat(call(delete("/api/v1/leaves/1")).status()).isEqualTo(204);
        problem(call(delete("/api/v1/leaves/1")), 404, "not_found");
    }

    @Test
    void settingsWrites() throws Exception {
        assertThat(call(json(post("/api/v1/settings/jira/test"), Map.of("url", "https://acme-pay.atlassian.net", "email", "a@b")))
                .text()).isEqualTo("{\"status\":\"ok\"}");
        problem(call(json(post("/api/v1/settings/epics"), Map.of("key", "PAY-1100"))), 409, "conflict");
        JsonNode epics = call(json(post("/api/v1/settings/epics"), Map.of("key", "PAY-1500"))).json().get("epics");
        assertThat(epics.findValuesAsText("key")).contains("PAY-1500");
        problem(call(json(post("/api/v1/extra-days"), Map.of("date", "2026-10-07", "note", ""))), 422, "invalid");
        assertThat(call(json(patch("/api/v1/members/an"), Map.of("role", "BAL"))).json().get("role").asText()).isEqualTo("BAL");
        assertThat(call(json(put("/api/v1/settings/alerts"), Map.of("soon_days", 3, "effort_warn_pct", 80, "held_days", 3,
                "review_rounds", 3, "capacity_pct", 100))).json().get("soon_days").asDouble()).isEqualTo(3);
        JsonNode pv = call(json(post("/api/v1/settings/name-rules/preview"), Map.of("rule", "[{function_name}] {step}", "sample", "[X] Create"))).json();
        assertThat(pv.get("sample_matches").asBoolean()).isTrue();
        assertThat(pv.get("step").asText()).isEqualTo("create");
    }

    @Test
    void structureEndpoints() throws Exception {
        assertThat(call(get("/api/v1/settings/structure")).json().get("phases").toString())
                .isEqualTo("[\"Prepare\",\"Implement_Coding\",\"Function Unit Test\"]");
        Map<String, Object> body = Map.of("phases", List.of("UC"),
                "tasks", List.of(Map.of("match", "[Implement_Coding] *", "phase", "UC", "prefix", "UCI")));
        JsonNode pv = call(json(post("/api/v1/settings/structure/preview"), body)).json();
        assertThat(pv.get("seen").findValues("key").stream().map(JsonNode::asText).toList()).contains("PAY-1102");
        for (JsonNode t : pv.get("seen")) {
            if (t.get("key").asText().equals("PAY-1102")) {
                assertThat(t.get("template").asInt()).isZero();
            }
        }
        problem(call(json(put("/api/v1/settings/structure"), Map.of("phases", List.of("UC"),
                "tasks", List.of(Map.of("match", "X", "phase", "QA"))))), 422, "invalid");
        JsonNode saved = call(json(put("/api/v1/settings/structure"), body)).json();
        assertThat(saved.get("tasks").get(0).get("prefix").asText()).isEqualTo("UCI");
        assertThat(saved.get("tasks").get(0).get("workflow").asBoolean()).isTrue();
    }

    @Test
    void syncEndpoint() throws Exception {
        assertThat(call(json(post("/api/v1/sync"), Map.of("full", false))).json().get("status").asText()).isEqualTo("ok");
    }
}
