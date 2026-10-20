package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.domain.Identity;

/**
 * Calls the API through MockMvc and checks problem details. Every call carries a CSRF token and, unless it says
 * {@link #as(String)} or {@link #anonymous()}, runs as the demo admin (minh.hoang@acme.vn, a bootstrap admin).
 */
abstract class ApiTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ADMIN = "minh.hoang@acme.vn";
    /** Request attribute: the call chose its own principal (or none), so the default admin is not applied. */
    private static final String CHOSEN = ApiTestSupport.class.getName() + ".principal";

    @Autowired
    MockMvc mvc;

    record Res(int status, String contentType, String text) {
        JsonNode json() throws Exception {
            return JSON.readTree(text);
        }
    }

    static UsernamePasswordAuthenticationToken signedIn(String email) {
        return new UsernamePasswordAuthenticationToken(new Identity(email, email.substring(0, email.indexOf('@'))), null, List.of());
    }

    Res call(MockHttpServletRequestBuilder r) throws Exception {
        TestSecurityContextHolder.clearContext(); // no principal carries over from an earlier call
        r.with(csrf()).with(req -> req.getAttribute(CHOSEN) != null ? req : authentication(signedIn(ADMIN)).postProcessRequest(req));
        MockHttpServletResponse res = mvc.perform(r).andReturn().getResponse();
        return new Res(res.getStatus(), res.getContentType(), res.getContentAsString(StandardCharsets.UTF_8));
    }

    /** Signed in as this e-mail. */
    static RequestPostProcessor as(String email) {
        return req -> {
            req.setAttribute(CHOSEN, email);
            return authentication(signedIn(email)).postProcessRequest(req);
        };
    }

    /** No principal of its own: only what the request's session holds, if anything. */
    static RequestPostProcessor anonymous() {
        return req -> {
            req.setAttribute(CHOSEN, "anonymous");
            return req;
        };
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, Object body) throws Exception {
        return r.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
    }

    /** The response is a problem detail with this status and code. */
    static JsonNode problem(Res r, int status, String code) throws Exception {
        assertThat(r.status()).as(r.text()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        JsonNode body = r.json();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        return body;
    }
}
