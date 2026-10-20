package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;
import tracker.adapters.web.auth.Open;
import tracker.adapters.web.auth.Requires;
import tracker.application.service.AccessService;
import tracker.domain.AccessStatus;
import tracker.domain.Identity;

/** Every API endpoint declares who may call it, and the rules hold through HTTP. */
@DemoTest
@AutoConfigureMockMvc
class AccessGateTest extends ApiTestSupport {
    static final String AN = "an.nguyen@acme.vn";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired
    AccessService access;

    @Test
    void everyApiHandlerDeclaresItsAccessRule() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (e.getKey().getPatternValues().stream().noneMatch(p -> p.startsWith("/api/"))) {
                continue;
            }
            checked++;
            HandlerMethod h = e.getValue();
            boolean open = h.hasMethodAnnotation(Open.class);
            boolean requires = h.hasMethodAnnotation(Requires.class);
            if (open == requires) {
                offenders.add(h.getBeanType().getSimpleName() + "." + h.getMethod().getName() + " " + e.getKey()
                        + (open ? " (both @Open and @Requires)" : " (neither @Open nor @Requires)"));
            }
        }
        assertThat(checked).as("API handlers found").isGreaterThan(40);
        assertThat(offenders).as("API handlers without exactly one access rule").isEmpty();
    }

    @Test
    void anonymousGets401() throws Exception {
        problem(call(get("/api/v1/anken").with(anonymous())), 401, "unauthenticated");
        problem(call(json(post("/api/v1/sync"), Map.of()).with(anonymous())), 401, "unauthenticated");
        assertThat(call(get("/api/v1/auth/info").with(anonymous())).status()).isEqualTo(200);
        problem(call(get("/api/v1/nope").with(anonymous())), 404, "not_found");
    }

    @Test
    @DirtiesContext
    void baCannotWriteThroughTheApi() throws Exception {
        String before = call(get("/api/v1/tickets/PAY-1213").with(as(AN))).json().get("card").get("status").asText();
        problem(call(json(patch("/api/v1/tickets/PAY-1213"), Map.of("status", "R")).with(as(AN))), 403, "forbidden");
        problem(call(json(post("/api/v1/planning/push"),
                Map.of("changes", List.of(Map.of("key", "PAY-1262", "start", "2026-10-12", "member_id", "binh")))).with(as(AN))),
                403, "forbidden");
        problem(call(json(put("/api/v1/settings/alerts"), Map.of("soon_days", 1, "effort_warn_pct", 1, "held_days", 1,
                "review_rounds", 1, "capacity_pct", 1)).with(as(AN))), 403, "forbidden");
        problem(call(json(post("/api/v1/anken/PAY-1100/functions"), Map.of()).with(as(AN))), 403, "forbidden");
        assertThat(call(get("/api/v1/tickets/PAY-1213").with(as(AN))).json().get("card").get("status").asText()).isEqualTo(before);
        assertThat(call(get("/api/v1/anken").with(as(AN))).status()).as("reads are allowed").isEqualTo(200);
    }

    @Test
    @DirtiesContext
    void baChangesOnlyTheirOwnLeave() throws Exception {
        Res own = call(json(post("/api/v1/leaves"), Map.of("member_id", "an", "type", "full", "date_from", "2026-11-02",
                "date_to", "2026-11-03")).with(as(AN)));
        assertThat(own.status()).as(own.text()).isEqualTo(201);
        problem(call(json(post("/api/v1/leaves"), Map.of("member_id", "binh", "type", "full", "date_from", "2026-11-02",
                "date_to", "2026-11-03")).with(as(AN))), 403, "forbidden");
        int binhLeave = leaveId("2026-10", "binh", "2026-10-20");
        problem(call(delete("/api/v1/leaves/" + binhLeave).with(as(AN))), 403, "forbidden");
        problem(call(json(put("/api/v1/leaves/" + binhLeave), Map.of("member_id", "an", "type", "full", "date_from", "2026-11-04",
                "date_to", "2026-11-04")).with(as(AN))), 403, "forbidden");
        problem(call(json(post("/api/v1/leaves/import"), Map.of("rows", List.of())).with(as(AN))), 403, "forbidden");
        assertThat(leaveId("2026-10", "binh", "2026-10-20")).as("binh's leave is still there").isEqualTo(binhLeave);
        int anLeave = leaveId("2026-10", "an", "2026-10-16");
        assertThat(call(delete("/api/v1/leaves/" + anLeave).with(as(AN))).status()).isEqualTo(204);
    }

    private int leaveId(String month, String member, String date) throws Exception {
        JsonNode view = call(get("/api/v1/calendar").param("month", month)).json();
        for (JsonNode row : view.get("rows")) {
            if (row.get("member_id").asText().equals(member)) {
                for (JsonNode c : row.get("cells")) {
                    if (c.get("date").asText().equals(date)) {
                        return c.get("leave").get("id").asInt();
                    }
                }
            }
        }
        throw new AssertionError("no leave of " + member + " on " + date);
    }

    @Test
    @DirtiesContext
    void disablingAUserTakesEffectOnTheNextRequest() throws Exception {
        assertThat(call(get("/api/v1/anken").with(as(AN))).status()).isEqualTo(200);
        var admin = access.viewer(new Identity(ADMIN, "Minh"));
        access.patchUser(admin, "an", new AccessService.UserPatch(null, null, null, false, null, null, AccessStatus.DISABLED, Map.of()));
        problem(call(get("/api/v1/anken").with(as(AN))), 403, "no_access");
        assertThat(call(get("/api/v1/me").with(as(AN))).json().get("access").asText()).isEqualTo("disabled");
    }

    @Test
    void missingCsrfIs403() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/v1/sync").contentType("application/json").content("{}")
                .with(authentication(signedIn(ADMIN)))).andReturn().getResponse();
        Res r = new Res(res.getStatus(), res.getContentType(), res.getContentAsString(StandardCharsets.UTF_8));
        problem(r, 403, "csrf");
    }
}
