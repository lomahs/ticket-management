package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import tracker.DemoTest;

/** Sign-in against the demo profile's embedded LDAP server, with a real session. */
@DemoTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthApiTest extends ApiTestSupport {

    Res login(MockHttpSession session, String username, String password) throws Exception {
        return call(json(post("/api/v1/auth/login"), Map.of("username", username, "password", password)).session(session)
                .with(anonymous()));
    }

    Res in(MockHttpSession session, MockHttpServletRequestBuilder r) throws Exception {
        return call(r.session(session).with(anonymous()));
    }

    @Test
    void infoIsPublicAndShowsTheDemoLogins() throws Exception {
        JsonNode info = call(get("/api/v1/auth/info").with(anonymous())).json();
        assertThat(info.get("mode").asText()).isEqualTo("demo");
        assertThat(info.get("demo_password").asText()).isEqualTo("demo");
        assertThat(info.get("demo_users").get(0).asText()).isEqualTo("minh (admin)");
    }

    @Test
    void loginReturnsMeAndKeepsTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        Res r = login(session, "an", "demo");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode me = r.json();
        assertThat(me.get("email").asText()).isEqualTo("an.nguyen@acme.vn");
        assertThat(me.get("member_id").asText()).isEqualTo("an");
        assertThat(me.get("access").asText()).isEqualTo("active");
        assertThat(me.get("role").asText()).isEqualTo("BA");
        assertThat(me.get("admin").asBoolean()).isFalse();
        assertThat(me.get("permissions").get("matrix").asText()).isEqualTo("read");
        assertThat(me.get("permissions").get("members").asText()).isEqualTo("none");
        assertThat(session.getId()).as("a new session id after login").isNotEqualTo(before);
        assertThat(in(session, get("/api/v1/me")).json().get("email").asText()).isEqualTo("an.nguyen@acme.vn");
    }

    @Test
    void usernameIsCaseInsensitive() throws Exception {
        assertThat(login(new MockHttpSession(), " AN ", "demo").status()).isEqualTo(200);
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        JsonNode p = problem(login(new MockHttpSession(), "an", "nope"), 401, "bad_credentials");
        // the same answer for a user that does not exist: the response does not tell which
        JsonNode q = problem(login(new MockHttpSession(), "nobody", "nope"), 401, "bad_credentials");
        assertThat(q.get("detail")).isEqualTo(p.get("detail"));
    }

    @Test
    void fiveFailuresLockTheUsername() throws Exception {
        // MockMvc requests come from 127.0.0.1: the lock is per username and client address
        for (int i = 0; i < 5; i++) {
            problem(login(new MockHttpSession(), i % 2 == 0 ? "an" : "An", "wrong"), 401, "bad_credentials");
        }
        problem(login(new MockHttpSession(), "an", "demo"), 429, "too_many_attempts");
        assertThat(login(new MockHttpSession(), "binh", "demo").status()).as("other usernames are not locked").isEqualTo(200);
    }

    @Test
    void theLockIsPerClientAddress() throws Exception {
        for (int i = 0; i < 5; i++) {
            problem(login(new MockHttpSession(), "an", "wrong"), 401, "bad_credentials");
        }
        problem(login(new MockHttpSession(), "an", "demo"), 429, "too_many_attempts");
        Res other = call(json(post("/api/v1/auth/login"), Map.of("username", "an", "password", "demo")).with(anonymous())
                .with(r -> {
                    r.setRemoteAddr("10.0.0.9");
                    return r;
                }));
        assertThat(other.status()).as("the same username from another client is not locked: " + other.text()).isEqualTo(200);
    }

    @Test
    void strangerCanRequestAccess() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Res r = login(session, "lan", "demo");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.json().get("access").asText()).isEqualTo("none");
        assertThat(r.json().get("member_id").isNull()).isTrue();
        assertThat(r.json().get("permissions").get("overview").asText()).isEqualTo("none");
        problem(in(session, get("/api/v1/anken")), 403, "no_access");
        Res req = in(session, json(post("/api/v1/me/access-request"), Map.of("note", "BookUI team")));
        assertThat(req.status()).as(req.text()).isEqualTo(200);
        assertThat(req.json().get("access").asText()).isEqualTo("requested");
    }

    @Test
    void bootstrapAdminLoginIsAdmin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Res r = login(session, "minh", "demo");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.json().get("admin").asBoolean()).isTrue();
        assertThat(r.json().get("permissions").get("members").asText()).isEqualTo("write");
        assertThat(in(session, get("/api/v1/settings/alerts")).status()).isEqualTo(200);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "an", "demo").status()).isEqualTo(200);
        assertThat(in(session, post("/api/v1/auth/logout")).status()).isEqualTo(204);
        assertThat(session.isInvalid()).isTrue();
        problem(in(session, get("/api/v1/me")), 401, "unauthenticated");
        problem(call(get("/api/v1/me").with(anonymous())), 401, "unauthenticated");
    }

    @Test
    void breakglassWorksOnlyWhenConfigured() throws Exception {
        problem(login(new MockHttpSession(), "breakglass", "glass-pass-1"), 401, "bad_credentials");
    }

    @Nested
    @TestPropertySource(properties = "tracker.breakglass-password-hash=" + WithBreakGlass.HASH)
    class WithBreakGlass extends ApiTestSupport { // its own MockMvc: the outer instance's belongs to the outer context
        /** bcrypt of "glass-pass-1" */
        static final String HASH = "$2a$10$6rgH3XNnxCkwvwCEqQIClOEzRk4I4l1sTNT7QxoOUhfXFu3p2/S.q";

        @Test
        void breakglassSignsInAsAdmin() throws Exception {
            MockHttpSession session = new MockHttpSession();
            Res r = call(json(post("/api/v1/auth/login"), Map.of("username", "breakglass", "password", "glass-pass-1"))
                    .session(session).with(anonymous()));
            assertThat(r.status()).as(r.text()).isEqualTo(200);
            assertThat(r.json().get("email").asText()).isEqualTo("breakglass@local");
            assertThat(r.json().get("admin").asBoolean()).isTrue();
            assertThat(call(get("/api/v1/settings/alerts").session(session).with(anonymous())).status()).isEqualTo(200);
            problem(call(json(post("/api/v1/auth/login"), Map.of("username", "breakglass", "password", "wrong")).with(anonymous())),
                    401, "bad_credentials");
        }
    }
}
