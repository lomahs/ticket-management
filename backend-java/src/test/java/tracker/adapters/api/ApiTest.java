package tracker.adapters.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.DemoDeps;
import tracker.application.Deps;

/** The HTTP API on demo data (ported from the original backend's API tests). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    MockMvc client;

    static MockMvc mvc(Deps d) {
        Jackson2ObjectMapperBuilder b = new Jackson2ObjectMapperBuilder();
        new JsonConfig().trackerJson().customize(b);
        return MockMvcBuilders.standaloneSetup(new SystemApi(d), new WorkApi(d), new AnkenApi(d), new PlanningApi(d),
                        new EffortApi(d), new CalendarApi(d), new TicketsApi(d), new SettingsApi(d),
                        new StaticFrontend(new StaticFrontend.StaticDir(null)))
                .setControllerAdvice(new ApiErrors())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new ResourceHttpMessageConverter(), new MappingJackson2HttpMessageConverter(b.build()))
                .build();
    }

    @BeforeAll
    void setUp() throws Exception {
        client = mvc(DemoDeps.demo(Files.createTempDirectory("api")));
    }

    record Res(int status, String contentType, String text) {
        JsonNode json() throws Exception {
            return JSON.readTree(text);
        }
    }

    static Res call(MockMvc m, RequestBuilder r) throws Exception {
        MockHttpServletResponse res = m.perform(r).andReturn().getResponse();
        return new Res(res.getStatus(), res.getContentType(), res.getContentAsString(StandardCharsets.UTF_8));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, Object body) throws Exception {
        return r.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
    }

    static JsonNode problem(Res r, int status, String code) throws Exception {
        assertThat(r.status()).as(r.text()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        JsonNode body = r.json();
        assertThat(body.get("code").asText()).isEqualTo(code);
        return body;
    }

    // --- reads ------------------------------------------------------------------------------------------------------

    @Test
    void metaAndMembers() throws Exception {
        JsonNode m = call(client, get("/api/v1/meta")).json();
        assertThat(m.get("mode").asText()).isEqualTo("demo");
        assertThat(m.get("today").asText()).isEqualTo("2026-10-07");
        assertThat(m.get("capabilities").toString()).isEqualTo("{\"jira_write\":true}");
        assertThat(m.get("sync").get("status").asText()).isEqualTo("ok");
        assertThat(m.get("sync").get("started_at").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?Z");
        JsonNode members = call(client, get("/api/v1/members")).json();
        assertThat(members.get(0).get("id").asText()).isEqualTo("an");
        assertThat(members.get(0).get("full_name").asText()).isEqualTo("Nguyễn Thị An");
        assertThat(members.get(0).get("jira_account_id").asText()).isEqualTo("acc-an");
    }

    @Test
    void myWork() throws Exception {
        JsonNode w = call(client, get("/api/v1/my-work").param("member", "binh").param("backlog", "week")).json();
        assertThat(w.get("delay").get(0).get("key").asText()).isEqualTo("PAY-1012");
        assertThat(w.get("backlog").get(0).get("kind").asText()).isEqualTo("overdue");
        problem(call(client, get("/api/v1/my-work").param("member", "zz")), 404, "not_found");
        JsonNode e = problem(call(client, get("/api/v1/my-work").param("member", "binh").param("backlog", "year")), 422, "invalid");
        assertThat(e.get("errors").get(0).toString()).isEqualTo("{\"field\":\"backlog\",\"message\":\"Input should be 'week' or 'month'\"}");
    }

    @Test
    void ankenOverviewDetailMatrix() throws Exception {
        assertThat(call(client, get("/api/v1/anken")).json().get(0).get("key").asText()).isEqualTo("PAY-1100");
        JsonNode d = call(client, get("/api/v1/anken/PAY-1100")).json();
        assertThat(d.get("card").get("late_functions").asInt()).isEqualTo(6);
        assertThat(d.get("attention").get(1).get("kind").asText()).isEqualTo("stuck_resolved");
        JsonNode m = call(client, get("/api/v1/anken/PAY-1100/matrix")).json();
        assertThat(m.get("stats").get("logged_h").asDouble()).isEqualTo(148);
        assertThat(m.get("functions")).hasSize(12);
        assertThat(m.get("functions").get(0).get("cells").get(0).get("tone").asText()).isEqualTo("clo");
        assertThat(m.get("functions").get(0).get("cells").get(0).has("is_current")).isTrue();
        problem(call(client, get("/api/v1/anken/NOPE-1/matrix")), 404, "not_found");
    }

    @Test
    void functionPreviewValidatesBody() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("parent_key", "PAY-1102", "name", "Installment", "ba", "an",
                "bal", "minh", "jm", "tanaka", "create_hours", 8));
        body.put("overrides", Arrays.asList(null, null, null, null, null));
        JsonNode p = call(client, json(post("/api/v1/anken/PAY-1100/functions/preview"), body)).json();
        assertThat(p.get("function_id").asText()).isEqualTo("F13");
        assertThat(p.get("steps").get(0).get("start").asText()).isEqualTo("2026-10-08");
        body.put("create_hours", -1);
        JsonNode err = problem(call(client, json(post("/api/v1/anken/PAY-1100/functions/preview"), body)), 422, "invalid");
        assertThat(err.get("errors").get(0).get("field").asText()).isEqualTo("create_hours");
        assertThat(err.get("detail").asText()).isEqualTo("Input should be greater than 0");
    }

    @Test
    void planningEffortCalendar() throws Exception {
        JsonNode p = call(client, get("/api/v1/planning").param("start", "2026-10-05").param("weeks", "2")).json();
        assertThat(p.get("start").asText()).isEqualTo("2026-10-05");
        assertThat(p.get("capacity")).hasSize(6);
        JsonNode pv = call(client, json(post("/api/v1/planning/preview"), Map.of("start", "2026-10-05",
                "changes", List.of(Map.of("key", "PAY-1262", "start", "2026-10-11", "member_id", "binh"))))).json();
        assertThat(pv.get("changes").get(0).get("day_off").toString()).isEqualTo("[[\"2026-10-11\",\"weekend\"]]");
        JsonNode ts = call(client, get("/api/v1/effort/timesheet").param("from", "2026-10-05").param("to", "2026-10-11")).json();
        assertThat(ts.get("days")).hasSize(6);
        assertThat(call(client, get("/api/v1/effort/timesheet/binh/2026-10-06")).json().get("entries")).isNotEmpty();
        assertThat(call(client, get("/api/v1/effort/analysis")).json().get("epic_key").asText()).isEqualTo("PAY-1100");
        assertThat(call(client, get("/api/v1/calendar").param("month", "2026-10")).json().get("rows").get(0).get("member_id").asText())
                .isEqualTo("an");
        JsonNode e = problem(call(client, get("/api/v1/planning").param("start", "2026-10-05").param("weeks", "3")), 422, "invalid");
        assertThat(e.get("detail").asText()).isEqualTo("Input should be less than or equal to 2");
    }

    @Test
    void ticketAndQa() throws Exception {
        JsonNode t = call(client, get("/api/v1/tickets/PAY-1213")).json();
        assertThat(t.get("card").get("rounds").asInt()).isEqualTo(3);
        assertThat(t.get("warnings").get(0).get("kind").asText()).isEqualTo("late");
        JsonNode a = call(client, json(post("/api/v1/qa"), Map.of("q", "Which tickets ping-pong for many rounds?"))).json();
        assertThat(a.get("text").asText()).contains("rounds");
    }

    @Test
    void settingsReads() throws Exception {
        JsonNode j = call(client, get("/api/v1/settings/jira")).json();
        assertThat(j.has("token")).isFalse();
        assertThat(j.get("token_masked").asText()).endsWith("••••••••");
        assertThat(call(client, get("/api/v1/settings/field-mapping")).json().get("catalog")).isNotEmpty();
        assertThat(call(client, get("/api/v1/settings/status-mapping")).json()).isNotEmpty();
        assertThat(call(client, get("/api/v1/settings/name-rules")).json().get("aliases").get("create").asText()).isEqualTo("Create");
        assertThat(call(client, get("/api/v1/holidays")).json().get(0).get("date").asText()).isEqualTo("2026-09-01");
        assertThat(call(client, get("/api/v1/extra-days")).json().toString()).isEqualTo("{\"2026-10-10\":\"Release support · go-live weekend\"}");
        assertThat(call(client, get("/api/v1/settings/working-hours")).json().get("days").toString()).isEqualTo("[1,2,3,4,5]");
        assertThat(call(client, get("/api/v1/settings/alerts")).json().get("soon_days").asDouble()).isEqualTo(2);
    }

    @Test
    void unknownRoutesAndMalformedBodies() throws Exception {
        Res r = call(client, get("/api/v1/nope"));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text()).isEqualTo("{\"detail\":\"Not Found\"}");
        JsonNode e = problem(call(client, post("/api/v1/qa").contentType(MediaType.APPLICATION_JSON).content("[]")), 422, "invalid");
        assertThat(e.get("errors").get(0).get("field").isNull()).isTrue();
        assertThat(e.get("detail").asText()).isEqualTo("Input should be a valid dictionary or object to extract fields from");
        problem(call(client, post("/api/v1/sync")), 422, "invalid");
        e = problem(call(client, delete("/api/v1/leaves/abc")), 422, "invalid");
        assertThat(e.get("errors").get(0).get("field").asText()).isEqualTo("leave_id");
    }

    // --- writes -----------------------------------------------------------------------------------------------------

    @TempDir
    Path tmp;

    MockMvc fresh() {
        return mvc(DemoDeps.demo(tmp));
    }

    @Test
    void ticketPatchAndPlanningPush() throws Exception {
        MockMvc f = fresh();
        assertThat(call(f, json(patch("/api/v1/tickets/PAY-1213"), Map.of("status", "R"))).json().get("card").get("status").asText())
                .isEqualTo("R");
        Res r = call(f, json(post("/api/v1/planning/push"),
                Map.of("changes", List.of(Map.of("key", "PAY-1262", "start", "2026-10-12", "member_id", "binh")))));
        assertThat(r.text()).isEqualTo("{\"keys\":[\"PAY-1262\"]}");
    }

    @Test
    void createFunction() throws Exception {
        Res r = call(fresh(), json(post("/api/v1/anken/PAY-1100/functions"), Map.of("parent_key", "PAY-1102", "name", "Installment",
                "ba", "an", "bal", "minh", "jm", "tanaka", "create_hours", 8)));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("keys").get(0).asText()).isEqualTo("PAY-1501");
    }

    @Test
    void leavesCrudImportExport() throws Exception {
        MockMvc f = fresh();
        Res r = call(f, json(post("/api/v1/leaves"), Map.of("member_id", "an", "type", "full", "date_from", "2026-11-02",
                "date_to", "2026-11-06", "note", "")));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text()).isEqualTo("{\"days\":5}");
        problem(call(f, json(post("/api/v1/leaves"), Map.of("member_id", "an", "type", "full", "date_from", "2026-11-06",
                "date_to", "2026-11-02"))), 422, "invalid");
        MockMultipartFile csv = new MockMultipartFile("file", "x.csv", "text/csv",
                "﻿Member,From,To,Type,Note\nChi,2026-12-01,2026-12-02,Half day,\n".getBytes(StandardCharsets.UTF_8));
        JsonNode pv = call(f, multipart("/api/v1/leaves/import/preview").file(csv)).json();
        assertThat(pv.get("file").asText()).isEqualTo("x.csv");
        assertThat(pv.get("rows").get(0).get("error").asText()).isEmpty();
        assertThat(pv.get("working_days").asInt()).isEqualTo(2);
        JsonNode sample = call(f, post("/api/v1/leaves/import/preview").param("sample", "true")).json();
        assertThat(sample.get("file").asText()).isEqualTo("leave-november.xlsx");
        assertThat(sample.get("rows")).hasSize(10);
        JsonNode res = call(f, json(post("/api/v1/leaves/import"), Map.of("rows", List.of(List.of("Chi", "2026-12-01", "2026-12-02",
                "Half day", ""))))).json();
        assertThat(res.toString()).isEqualTo("{\"imported\":2,\"month\":\"2026-12\"}");
        Res x = call(f, get("/api/v1/leaves/export").param("month", "2026-12"));
        assertThat(x.contentType()).startsWith("application/vnd.ms-excel");
        assertThat(x.text()).contains("Lê Minh Chi");
        assertThat(call(f, get("/api/v1/leaves/template")).text()).startsWith("﻿Member");
        problem(call(f, multipart("/api/v1/leaves/import/preview").file(new MockMultipartFile("file", "x.pdf", "application/pdf",
                "%PDF".getBytes()))), 422, "invalid");
        Res d = call(f, delete("/api/v1/leaves/1"));
        assertThat(d.status()).isEqualTo(204);
        problem(call(f, delete("/api/v1/leaves/1")), 404, "not_found");
    }

    @Test
    void importRejectsOversizedFiles() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 30];
        Arrays.fill(big, (byte) 'x');
        byte[] header = "Member,From,To,Type,Note\n".getBytes();
        System.arraycopy(header, 0, big, 0, header.length);
        JsonNode err = problem(call(fresh(), multipart("/api/v1/leaves/import/preview").file(
                new MockMultipartFile("file", "big.csv", "text/csv", big))), 422, "invalid");
        assertThat(err.get("detail").asText()).contains("5 MB");
    }

    @Test
    void settingsWrites() throws Exception {
        MockMvc f = fresh();
        assertThat(call(f, json(post("/api/v1/settings/jira/test"), Map.of("url", "https://x.example.com", "email", "a@b",
                "token", "short"))).text()).isEqualTo("{\"status\":\"fail\"}");
        problem(call(f, json(post("/api/v1/settings/epics"), Map.of("key", "PAY-1100"))), 409, "conflict");
        JsonNode epics = call(f, json(post("/api/v1/settings/epics"), Map.of("key", "PAY-1500"))).json().get("epics");
        assertThat(epics.findValuesAsText("key")).contains("PAY-1500");
        problem(call(f, json(post("/api/v1/extra-days"), Map.of("date", "2026-10-07", "note", ""))), 422, "invalid");
        assertThat(call(f, json(patch("/api/v1/members/an"), Map.of("role", "BAL"))).json().get("role").asText()).isEqualTo("BAL");
        assertThat(call(f, json(put("/api/v1/settings/alerts"), Map.of("soon_days", 3, "effort_warn_pct", 80, "held_days", 3,
                "review_rounds", 3, "capacity_pct", 100))).json().get("soon_days").asDouble()).isEqualTo(3);
        JsonNode pv = call(f, json(post("/api/v1/settings/name-rules/preview"), Map.of("rule", "{step}", "sample", "Create"))).json();
        assertThat(pv.get("sample_matches").asBoolean()).isTrue();
        assertThat(pv.get("step").asText()).isEqualTo("create");
    }

    @Test
    void syncEndpoint() throws Exception {
        assertThat(call(fresh(), json(post("/api/v1/sync"), Map.of("full", false))).json().get("status").asText()).isEqualTo("ok");
    }

    @Test
    void writesAreDisabledWithoutJiraWriter() throws Exception {
        MockMvc c = mvc(DemoDeps.demo(tmp, false));
        assertThat(call(c, get("/api/v1/meta")).json().get("capabilities").toString()).isEqualTo("{\"jira_write\":false}");
        problem(call(c, json(patch("/api/v1/tickets/PAY-1213"), Map.of("status", "R"))), 403, "capability_disabled");
        problem(call(c, json(post("/api/v1/planning/push"), Map.of("changes", List.of()))), 403, "capability_disabled");
    }
}
