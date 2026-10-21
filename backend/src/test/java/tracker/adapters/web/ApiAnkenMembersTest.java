package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;
import tracker.application.ports.AccessRepository;
import tracker.application.ports.AnkenMemberRepository;
import tracker.application.ports.MemberRepository;
import tracker.domain.AccessStatus;
import tracker.domain.AnkenScopeSetting;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;

/** The anken member endpoints over HTTP; `an` is a BA of PAY-1100. */
@DemoTest
@AutoConfigureMockMvc
@DirtiesContext
class ApiAnkenMembersTest extends ApiTestSupport {
    static final String AN = "an.nguyen@acme.vn";

    @Autowired
    AnkenMemberRepository ankenMembers;
    @Autowired
    MemberRepository members;
    @Autowired
    AccessRepository access;

    @Test
    void removalTakesEffectOnTheNextRequest() throws Exception {
        assertThat(call(get("/api/v1/anken/PAY-1100").with(as(AN))).status()).isEqualTo(200);
        Res removed = call(delete("/api/v1/anken/PAY-1100/members/an"));
        assertThat(removed.status()).as(removed.text()).isEqualTo(200);
        problem(call(get("/api/v1/anken/PAY-1100").with(as(AN))), 404, "not_found");
    }

    @Test
    void baCannotListMembers() throws Exception {
        problem(call(get("/api/v1/anken/PAY-1100/members").with(as(AN))), 403, "forbidden");
        problem(call(json(post("/api/v1/anken/PAY-1100/members"), Map.of("member_id", "binh")).with(as(AN))), 403, "forbidden");
    }

    @Test
    void adminListsAddsAndSetsRoles() throws Exception {
        JsonNode view = call(get("/api/v1/anken/PAY-1100/members")).json();
        assertThat(view.get("epic_key").asText()).isEqualTo("PAY-1100");
        assertThat(view.get("members").size()).isGreaterThan(0);

        Res created = call(json(post("/api/v1/people"), Map.of("email", "kai@acme.vn", "name", "Kai", "role", "BA",
                "epics", List.of("PAY-1100"))));
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        String id = created.json().get("id").asText();
        assertThat(ankenMembers.exists("PAY-1100", id)).isTrue();
        problem(call(json(post("/api/v1/people"), Map.of("email", "kai@acme.vn", "name", "Kai", "role", "BA",
                "epics", List.of("PAY-1100")))), 409, "conflict");

        Res patched = call(json(patch("/api/v1/people/" + id), Map.of("role", "BAL")));
        assertThat(patched.json().get("role").asText()).isEqualTo("BAL");
        Res cleared = call(json(patch("/api/v1/people/" + id), Map.of("clear_role", true)));
        assertThat(cleared.json().get("role").isNull()).isTrue();
        problem(call(json(patch("/api/v1/people/" + id), Map.of())), 422, "invalid");
    }

    @Test
    void scopedPimOnlyManagesTheirOwnAnken() throws Exception {
        members.save(new Member("pia", "Pia", "Pia Planner", Role.PIM, "VN", "#abcdef", "pia@acme.vn", Permission.USER, false, null));
        access.saveStatus("pia", AccessStatus.ACTIVE);
        access.saveAnkenScope("pia", AnkenScopeSetting.MEMBERS);
        ankenMembers.add("PAY-1100", "pia", "test", Instant.now().toString());
        assertThat(call(get("/api/v1/anken/PAY-1100/members").with(as("pia@acme.vn"))).status()).isEqualTo(200);
        problem(call(get("/api/v1/anken/PAY-1300/members").with(as("pia@acme.vn"))), 404, "not_found");
        problem(call(json(post("/api/v1/anken/PAY-1300/members"), Map.of("member_id", "an")).with(as("pia@acme.vn"))), 404, "not_found");
        problem(call(json(post("/api/v1/people"), Map.of("email", "q@acme.vn", "name", "Q", "role", "PM",
                "epics", List.of("PAY-1100"))).with(as("pia@acme.vn"))), 403, "forbidden");
        JsonNode e = problem(call(json(post("/api/v1/people"), Map.of("email", "r@acme.vn", "name", "R", "role", "BA",
                "epics", List.of())).with(as("pia@acme.vn"))), 422, "invalid");
        assertThat(e.get("errors").get(0).get("field").asText()).isEqualTo("epics");
        assertThat(call(json(post("/api/v1/people"), Map.of("email", "r@acme.vn", "name", "R", "role", "BA",
                "epics", List.of()))).status()).isEqualTo(201);
        problem(call(json(post("/api/v1/people"), Map.of("email", "q@acme.vn", "name", "Q", "role", "BA",
                "epics", List.of("PAY-1300"))).with(as("pia@acme.vn"))), 404, "not_found");
    }

    @Test
    void anotherAnkenIs404() throws Exception {
        problem(call(get("/api/v1/anken/PAY-9999/members")), 404, "not_found");
    }
}
