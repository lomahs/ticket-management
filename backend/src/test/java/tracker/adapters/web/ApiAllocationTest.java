package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.AllocationRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.MemberRepository;
import tracker.domain.AccessStatus;
import tracker.domain.Allocation;
import tracker.domain.AnkenScopeSetting;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;

/**
 * The allocation endpoints over HTTP; `an` is a BA of PAY-1100 and PAY-1300, `pia` a PIM who sees PAY-1100 only and `zoe` a
 * member of PAY-1300 only.
 */
@DemoTest
@AutoConfigureMockMvc
@DirtiesContext
class ApiAllocationTest extends ApiTestSupport {
    static final String AN = "an.nguyen@acme.vn";

    static final String PIM = "pia@acme.vn";

    @Autowired
    JdbcClient jdbc;
    @Autowired
    AllocationRepository allocations;
    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    MemberRepository members;
    @Autowired
    AccessRepository access;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM allocations").update();
        String now = Instant.now().toString();
        members.save(new Member("pia", "Pia", "Pia Planner", Role.PIM, "VN", "#abcdef", PIM, Permission.USER, false, "acc-pia"));
        access.saveStatus("pia", AccessStatus.ACTIVE);
        access.saveAnkenScope("pia", AnkenScopeSetting.MEMBERS);
        ankenMembers.add("PAY-1100", "pia", "test", now);
        members.save(new Member("zoe", "Zoe", "Zoe Outsider", Role.BA, "VN", "#123456"));
        ankenMembers.add("PAY-1300", "zoe", "test", now);
        ankenMembers.add("PAY-1100", "an", "test", now);
        ankenMembers.add("PAY-1300", "an", "test", now);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void baCannotRead() throws Exception {
        problem(call(get("/api/v1/people/binh/allocations").with(as(AN))), 403, "forbidden");
        problem(call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 5))).with(as(AN))),
                403, "forbidden");
    }

    @Test
    void putOverHttp() throws Exception {
        Res r = call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 5))));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(body.get("steps").get(0).get("hours").get("PAY-1100").asDouble()).isEqualTo(5.0);
        assertThat(call(get("/api/v1/people/binh/allocations")).json().get("steps")).hasSize(1);
    }

    @Test
    void badDateAndTotalAreRefused() throws Exception {
        problem(call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "soon", "hours", Map.of()))), 400, "invalid");
        problem(call(json(put("/api/v1/people/binh/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 9)))), 422,
                "invalid");
    }

    @Test
    void scopedPimCannotReachAMemberOutsideTheirAnken() throws Exception {
        problem(call(get("/api/v1/people/zoe/allocations").with(as(PIM))), 404, "not_found");
        problem(call(json(put("/api/v1/people/zoe/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 1)))
                .with(as(PIM))), 404, "not_found");
        assertThat(allocations.findByMember("zoe")).isEmpty();
    }

    /** The PIM sees an's PAY-1100 hours only: no PAY-1300 epic, hours, nor the date and author of a PAY-1300-only step. */
    @Test
    void scopedPimSeesOnlyTheirAnkenInTheTimeline() throws Exception {
        allocations.save(new Allocation("an", "PAY-1100", "2026-09-01", 5), "minh.hoang@acme.vn", "2026-09-01T00:00:00Z");
        allocations.save(new Allocation("an", "PAY-1300", "2026-09-01", 3), "minh.hoang@acme.vn", "2026-09-01T00:00:00Z");
        allocations.save(new Allocation("an", "PAY-1300", "2026-09-15", 2), "secret.pm@acme.vn", "2026-09-15T00:00:00Z");
        Res r = call(get("/api/v1/people/an/allocations").with(as(PIM)));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(body.get("epics")).hasSize(1);
        assertThat(body.get("epics").get(0).asText()).isEqualTo("PAY-1100");
        assertThat(body.get("steps")).hasSize(1);
        assertThat(body.get("steps").get(0).get("from").asText()).isEqualTo("2026-09-01");
        assertThat(fieldNames(body.get("steps").get(0).get("hours"))).containsExactly("PAY-1100");
        assertThat(r.text()).doesNotContain("PAY-1300").doesNotContain("2026-09-15").doesNotContain("secret.pm");
    }

    @Test
    void scopedPimCannotSetAHiddenAnken() throws Exception {
        JsonNode p = problem(call(json(put("/api/v1/people/an/allocations"), Map.of("from", "2026-10-01", "hours",
                Map.of("PAY-1100", 1, "PAY-1300", 1))).with(as(PIM))), 422, "invalid");
        assertThat(p.toString()).contains("hours.PAY-1300");
        assertThat(allocations.findByMember("an")).isEmpty();
    }

    @Test
    void anOutOfRangeValueIsRefused() throws Exception {
        JsonNode p = problem(call(json(put("/api/v1/people/an/allocations"), Map.of("from", "2026-10-01", "hours", Map.of("PAY-1100", 25)))),
                422, "invalid");
        assertThat(p.get("errors").get(0).get("field").asText()).isEqualTo("hours.PAY-1100");
        assertThat(allocations.findByMember("an")).isEmpty();
    }
}
