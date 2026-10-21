package tracker.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.ldap.authentication.LdapAuthenticator;
import org.springframework.test.context.TestPropertySource;

import tracker.TrackerTest;
import tracker.adapters.web.auth.ActiveDirectoryAuthenticator;

/** {@code tracker.ldap.ad-domain} signs in against Active Directory without a service account. */
@TrackerTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.ldap.urls=ldap://127.0.0.1:1", "tracker.ldap.ad-domain= @acme.vn "})
class ApiActiveDirectoryModeTest extends ApiTestSupport {
    @Autowired
    LdapAuthenticator authenticator;

    @Test
    void theDomainSwitchesToTheActiveDirectoryBind() throws Exception {
        assertThat(authenticator).isInstanceOf(ActiveDirectoryAuthenticator.class);
        problem(call(json(post("/api/v1/auth/login"), Map.of("username", "an", "password", "secret")).with(anonymous())), 503,
                "ldap_unavailable");
    }
}
