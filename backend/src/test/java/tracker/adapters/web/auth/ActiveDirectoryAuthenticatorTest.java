package tracker.adapters.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SimpleBindRequest;

import tracker.domain.Identity;

/** Sign-in without a service account, against an in-memory directory that binds by user principal name like AD. */
class ActiveDirectoryAuthenticatorTest {
    static final String BASE = "dc=acme,dc=vn";
    static final String AN = "cn=Nguyen An,ou=hcm," + BASE;
    static final String NO_MAIL = "cn=No Mail,ou=hcm," + BASE;
    static final Map<String, String> PRINCIPALS = Map.of("an.nguyen@acme.vn", AN, "nomail@acme.vn", NO_MAIL);

    static InMemoryDirectoryServer server;

    @BeforeAll
    static void start() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.setSchema(null); // AD's user class and userPrincipalName are not in the standard schema
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) throws LDAPException {
                // like Active Directory: a bind by user@domain instead of a DN; an unknown one is invalid credentials (49)
                SimpleBindRequest bind = request.getRequest();
                String name = bind.getBindDN().toLowerCase(Locale.ROOT);
                if (name.contains("@")) {
                    String dn = PRINCIPALS.get(name);
                    if (dn == null) {
                        throw new LDAPException(ResultCode.INVALID_CREDENTIALS);
                    }
                    request.setRequest(new SimpleBindRequest(dn, bind.getPassword().getValue()));
                }
            }
        });
        server = new InMemoryDirectoryServer(config);
        server.add("dn: " + BASE, "objectClass: domain", "dc: acme");
        server.add("dn: ou=hcm," + BASE, "objectClass: organizationalUnit", "ou: hcm");
        server.add("dn: " + AN, "objectClass: user", "cn: Nguyen An", "sAMAccountName: an.nguyen",
                "userPrincipalName: an.nguyen@acme.vn", "mail: an.nguyen@acme.vn", "displayName: Nguyễn An", "userPassword: secret");
        // another account whose sAMAccountName is the same text: never the one signed in
        server.add("dn: cn=Other,ou=hcm," + BASE, "objectClass: user", "cn: Other", "sAMAccountName: an.nguyen@acme.vn",
                "userPrincipalName: other@acme.vn", "mail: other@acme.vn", "userPassword: secret");
        server.add("dn: " + NO_MAIL, "objectClass: user", "cn: No Mail", "userPrincipalName: nomail@acme.vn", "userPassword: secret");
        server.startListening();
    }

    @AfterAll
    static void stop() {
        server.shutDown(true);
    }

    static LdapLogin login(String url) {
        return new LdapLogin(new ActiveDirectoryAuthenticator("acme.vn", url, BASE, Map.of()),
                new LdapLookup("(unused)", "mail", "displayName"));
    }

    static LdapLogin login() {
        return login("ldap://127.0.0.1:" + server.getListenPort());
    }

    @Test
    void signsInWithTheNameBeforeTheDomainOrTheFullPrincipalName() {
        assertThat(login().authenticate("an.nguyen", "secret")).isEqualTo(new Identity("an.nguyen@acme.vn", "Nguyễn An"));
        assertThat(login().authenticate("AN.NGUYEN@acme.vn", "secret")).isEqualTo(new Identity("an.nguyen@acme.vn", "Nguyễn An"));
    }

    @Test
    void aWrongPasswordOrAnUnknownUserLookTheSame() {
        assertThatThrownBy(() -> login().authenticate("an.nguyen", "wrong")).isInstanceOf(LdapLogin.Rejected.class)
                .hasMessage(LdapLogin.WRONG);
        assertThatThrownBy(() -> login().authenticate("nobody", "secret")).isInstanceOf(LdapLogin.Rejected.class)
                .hasMessage(LdapLogin.WRONG);
        // an empty password would be an anonymous bind that succeeds
        assertThatThrownBy(() -> login().authenticate("an.nguyen", "")).isInstanceOf(LdapLogin.Rejected.class)
                .hasMessage(LdapLogin.WRONG);
    }

    @Test
    void anAccountWithoutMailIsRejected() {
        assertThatThrownBy(() -> login().authenticate("nomail", "secret")).isInstanceOf(LdapLogin.Rejected.class)
                .hasMessage(LdapLogin.NO_MAIL);
    }

    @Test
    void aDirectoryThatCannotBeReachedIsUnavailable() {
        assertThatThrownBy(() -> login("ldap://127.0.0.1:1").authenticate("an.nguyen", "secret"))
                .isInstanceOf(LdapLogin.Unavailable.class);
    }
}
