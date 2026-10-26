package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;

/** The HTTP API on the demo data: JSON shapes, request validation and problem details. */
@DemoTest
@AutoConfigureMockMvc
class ApiReadsTest extends ApiTestSupport {

    @Test
    void metaAndMembers() throws Exception {
        JsonNode m = call(get("/api/v1/meta")).json();
        assertThat(m.get("mode").asText()).isEqualTo("demo");
        assertThat(m.get("today").asText()).isEqualTo("2026-10-07");
        assertThat(m.get("capabilities").toString()).isEqualTo("{\"jira_write\":true,\"planning_push\":true}");
        assertThat(m.get("sync").get("status").asText()).isEqualTo("ok");
        assertThat(m.get("sync").get("started_at").asText()).matches("2026-10-0[67]T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        JsonNode members = call(get("/api/v1/members")).json();
        assertThat(members.get(0).get("id").asText()).isEqualTo("an");
        assertThat(members.get(0).get("full_name").asText()).isEqualTo("Nguyễn Thị An");
        assertThat(members.get(0).get("jira_account_id").asText()).isEqualTo("acc-an");
    }

    @Test
    void myWork() throws Exception {
        JsonNode w = call(get("/api/v1/my-work").param("member", "binh").param("backlog", "week")).json();
        assertThat(w.get("delay").get(0).get("key").asText()).isEqualTo("PAY-1012");
        assertThat(w.get("backlog").get(0).get("kind").asText()).isEqualTo("overdue");
        problem(call(get("/api/v1/my-work").param("member", "zz")), 404, "not_found");
        JsonNode e = problem(call(get("/api/v1/my-work").param("member", "binh").param("backlog", "year")), 400, "invalid");
        assertThat(e.get("errors").get(0).toString()).isEqualTo("{\"field\":\"backlog\",\"message\":\"must be week or month\"}");
        e = problem(call(get("/api/v1/my-work")), 400, "invalid");
        assertThat(e.get("errors").get(0).toString()).isEqualTo("{\"field\":\"member\",\"message\":\"is required\"}");
    }

    @Test
    void ankenOverviewDetailMatrix() throws Exception {
        assertThat(call(get("/api/v1/anken")).json().get(0).get("key").asText()).isEqualTo("PAY-1100");
        JsonNode d = call(get("/api/v1/anken/PAY-1100")).json();
        assertThat(d.get("card").get("late_functions").asInt()).isEqualTo(6);
        assertThat(d.get("attention").get(1).get("kind").asText()).isEqualTo("stuck_resolved");
        JsonNode m = call(get("/api/v1/anken/PAY-1100/matrix")).json();
        assertThat(m.get("stats").get("logged_h").asDouble()).isEqualTo(148);
        assertThat(m.get("functions")).hasSize(12);
        assertThat(m.get("functions").get(0).get("cells").get(0).get("tone").asText()).isEqualTo("clo");
        assertThat(m.get("functions").get(0).get("cells").get(0).has("is_current")).isTrue();
        assertThat(call(get("/api/v1/anken/PAY-1100/matrix?tasks=PAY-1101&tasks=PAY-1103")).json().get("task_keys").toString())
                .isEqualTo("[\"PAY-1101\",\"PAY-1103\"]");
        assertThat(call(get("/api/v1/anken/PAY-1100/matrix?tasks=PAY-1101,PAY-1102")).json().get("task_keys")).hasSize(2);
        problem(call(get("/api/v1/anken/NOPE-1/matrix")), 404, "not_found");
    }

    @Test
    void ticketListByLabel() throws Exception {
        JsonNode list = call(get("/api/v1/anken/PAY-1100/tickets?label=overdue")).json();
        assertThat(list).isNotEmpty();
        int prev = Integer.MAX_VALUE;
        for (JsonNode t : list) {
            assertThat(t.get("schedule").get("finish").asText()).isEqualTo("overdue");
            int days = t.get("schedule").get("finish_days").asInt();
            assertThat(days).isLessThanOrEqualTo(prev);
            prev = days;
        }
        JsonNode inPhase = call(get("/api/v1/anken/PAY-1100/tickets?label=overdue&phase=Implement_Coding")).json();
        JsonNode allOverdue = call(get("/api/v1/anken/PAY-1100/tickets?label=overdue")).json();
        assertThat(inPhase).isNotEmpty().hasSizeLessThan(allOverdue.size());
        int phaseOverdue = -1;
        for (JsonNode ph : call(get("/api/v1/anken/PAY-1100")).json().get("card").get("phases")) {
            if (ph.get("name").asText().equals("Implement_Coding")) {
                phaseOverdue = ph.get("schedule").get("overdue").asInt();
            }
        }
        assertThat(inPhase).hasSize(phaseOverdue);
        Set<String> allKeys = new HashSet<>();
        allOverdue.forEach(t -> allKeys.add(t.get("key").asText()));
        for (JsonNode t : inPhase) {
            assertThat(allKeys).contains(t.get("key").asText());
        }
        JsonNode all = call(get("/api/v1/anken/PAY-1100/tickets?label=over_burn")).json();
        assertThat(all).isNotEmpty();
        double prevDiff = Double.MAX_VALUE;
        String prevKey = "";
        for (JsonNode t : all) {
            assertThat(t.get("schedule").get("effort").asText()).isEqualTo("over_burn");
            double diff = Math.abs(t.get("schedule").get("diff_h").asDouble());
            assertThat(diff).isLessThanOrEqualTo(prevDiff);
            if (diff == prevDiff) {
                assertThat(t.get("key").asText().compareTo(prevKey)).isPositive();
            }
            prevDiff = diff;
            prevKey = t.get("key").asText();
        }
        problem(call(get("/api/v1/anken/PAY-1100/tickets?label=overdue&phase=Other")), 404, "not_found");
        problem(call(get("/api/v1/anken/PAY-1100/tickets?label=bogus")), 400, "invalid");
        problem(call(get("/api/v1/anken/PAY-1100/tickets?label=overdue&phase=Nope")), 404, "not_found");
    }

    @Test
    void functionPreviewValidatesTheBody() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("parent_key", "PAY-1102", "name", "Installment", "ba", "an",
                "bal", "minh", "jm", "tanaka", "create_hours", 8));
        body.put("overrides", Arrays.asList(null, null, null, null, null));
        JsonNode p = call(json(post("/api/v1/anken/PAY-1100/functions/preview"), body)).json();
        assertThat(p.get("function_id").asText()).isEqualTo("F13");
        assertThat(p.get("steps").get(0).get("start").asText()).isEqualTo("2026-10-08");
        body.put("create_hours", -1);
        JsonNode err = problem(call(json(post("/api/v1/anken/PAY-1100/functions/preview"), body)), 400, "invalid");
        assertThat(err.get("errors").get(0).get("field").asText()).isEqualTo("create_hours");
        assertThat(err.get("detail").asText()).isEqualTo("create_hours: must be greater than 0");
        body.put("create_hours", 8);
        body.put("overrides", Arrays.asList(null, Map.of("start", "10/10/2026")));
        body.remove("ba");
        err = problem(call(json(post("/api/v1/anken/PAY-1100/functions/preview"), body)), 400, "invalid");
        assertThat(err.get("errors").toString()).isEqualTo("[{\"field\":\"ba\",\"message\":\"must not be null\"},"
                + "{\"field\":\"overrides.1.start\",\"message\":\"must be a date (YYYY-MM-DD)\"}]");
    }

    @Test
    void planningEffortCalendar() throws Exception {
        JsonNode p = call(get("/api/v1/planning").param("start", "2026-10-05").param("weeks", "2")).json();
        assertThat(p.get("start").asText()).isEqualTo("2026-10-05");
        assertThat(p.get("capacity")).hasSize(6);
        JsonNode pv = call(json(post("/api/v1/planning/preview"), Map.of("start", "2026-10-05",
                "changes", List.of(Map.of("key", "PAY-1262", "start", "2026-10-11", "member_id", "binh"))))).json();
        assertThat(pv.get("changes").get(0).get("day_off").toString()).isEqualTo("[{\"date\":\"2026-10-11\",\"reason\":\"weekend\"}]");
        JsonNode ts = call(get("/api/v1/effort/timesheet").param("from", "2026-10-05").param("to", "2026-10-11")).json();
        assertThat(ts.get("days")).hasSize(6);
        assertThat(call(get("/api/v1/effort/timesheet/binh/2026-10-06")).json().get("entries")).isNotEmpty();
        assertThat(call(get("/api/v1/effort/analysis")).json().get("epic_key").asText()).isEqualTo("PAY-1100");
        assertThat(call(get("/api/v1/calendar").param("month", "2026-10")).json().get("rows").get(0).get("member_id").asText())
                .isEqualTo("an");
    }

    @Test
    void parameterAndBodyErrors() throws Exception {
        JsonNode e = problem(call(get("/api/v1/planning").param("start", "2026-10-05").param("weeks", "3")), 400, "invalid");
        assertThat(e.get("detail").asText()).isEqualTo("weeks: must be less than or equal to 2");
        e = problem(call(get("/api/v1/planning").param("start", "2026-10-05").param("weeks", "x")), 400, "invalid");
        assertThat(e.get("detail").asText()).isEqualTo("weeks: must be an integer");
        e = problem(call(json(post("/api/v1/leaves"), Map.of("member_id", "an", "type", "holiday", "date_from", "2026-11-02",
                "date_to", "2026-11-02"))), 400, "invalid");
        assertThat(e.get("detail").asText()).isEqualTo("type: must be one of: full, half, fullTent, halfTent");
        e = problem(call(post("/api/v1/qa").contentType(MediaType.APPLICATION_JSON).content("[]")), 400, "invalid");
        assertThat(e.get("errors").get(0).get("field").isNull()).isTrue();
        assertThat(e.get("detail").asText()).isEqualTo("The body must be a JSON object");
        e = problem(call(post("/api/v1/qa").contentType(MediaType.APPLICATION_JSON).content("{\"q\": ")), 400, "invalid");
        assertThat(e.get("detail").asText()).isEqualTo("The body is not valid JSON");
        e = problem(call(post("/api/v1/sync").contentType(MediaType.APPLICATION_JSON)), 400, "invalid");
        assertThat(e.get("detail").asText()).isEqualTo("A request body is required");
        e = problem(call(delete("/api/v1/leaves/abc")), 400, "invalid");
        assertThat(e.get("errors").get(0).get("field").asText()).isEqualTo("leave_id");
    }

    @Test
    void unknownRoutesAreProblems() throws Exception {
        JsonNode e = problem(call(get("/api/v1/nope")), 404, "not_found");
        assertThat(e.get("instance").asText()).isEqualTo("/api/v1/nope");
        problem(call(delete("/api/v1/meta")), 405, "method_not_allowed");
    }

    @Test
    void ticketAndQa() throws Exception {
        JsonNode t = call(get("/api/v1/tickets/PAY-1213")).json();
        assertThat(t.get("card").get("rounds").asInt()).isEqualTo(3);
        assertThat(t.get("warnings").get(0).get("kind").asText()).isEqualTo("late");
        assertThat(t.get("jira_url").asText()).isEqualTo("https://acme-pay.atlassian.net/browse/PAY-1213");
        JsonNode a = call(json(post("/api/v1/qa"), Map.of("q", "Which tickets ping-pong for many rounds?"))).json();
        assertThat(a.get("text").asText()).contains("rounds");
    }

    @Test
    void structurePerAnken() throws Exception {
        List<String> epics = call(get("/api/v1/settings/structure?anken=PAY-1100")).json().get("seen").findValues("epic_key").stream()
                .map(JsonNode::asText).distinct().toList();
        assertThat(epics).containsExactly("PAY-1100");
        assertThat(call(get("/api/v1/settings/structure")).json().get("seen").findValues("epic_key").stream().map(JsonNode::asText)
                .distinct().count()).isGreaterThan(1);
        problem(call(get("/api/v1/settings/structure?anken=NOPE-1")), 404, "not_found");
    }

    @Test
    void settingsReads() throws Exception {
        JsonNode j = call(get("/api/v1/settings/jira")).json();
        assertThat(j.has("token")).isFalse();
        assertThat(j.get("token_masked").asText()).endsWith("••••••••");
        assertThat(call(get("/api/v1/settings/field-mapping")).json().get("catalog")).isNotEmpty();
        assertThat(call(get("/api/v1/settings/status-mapping")).json()).isNotEmpty();
        assertThat(call(get("/api/v1/settings/name-rules")).json().get("aliases").get("create").asText()).isEqualTo("Create");
        assertThat(call(get("/api/v1/holidays")).json().get(0).get("date").asText()).isEqualTo("2026-09-01");
        assertThat(call(get("/api/v1/extra-days")).json().toString()).isEqualTo("{\"2026-10-10\":\"Release support · go-live weekend\"}");
        assertThat(call(get("/api/v1/settings/working-hours")).json().get("days").toString()).isEqualTo("[1,2,3,4,5]");
        assertThat(call(get("/api/v1/settings/alerts")).json().get("soon_days").asDouble()).isEqualTo(2);
    }

    @Test
    void healthEndpoint() throws Exception {
        assertThat(call(get("/actuator/health")).json().get("status").asText()).isEqualTo("UP");
    }
}
