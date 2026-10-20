package tracker.adapters.web.auth;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.ldap.authentication.LdapAuthenticator;
import org.springframework.stereotype.Component;

import tracker.application.service.AccessService;
import tracker.domain.Identity;

/** Checks a username and password against the directory (a bind as the user) and reads who they are. */
@Component
public class LdapLogin {

    /** The username or the password is wrong; the message never says which. */
    public static class Rejected extends RuntimeException {
        Rejected(String message) {
            super(message);
        }
    }

    /** The directory cannot be reached or answered with an error. */
    public static class Unavailable extends RuntimeException {
        Unavailable(Throwable cause) {
            super("The directory cannot be reached", cause);
        }
    }

    public static final String WRONG = "Wrong username or password";
    public static final String NO_MAIL = "Your directory account has no e-mail";

    private final LdapAuthenticator authenticator;
    private final LdapLookup lookup;

    public LdapLogin(LdapAuthenticator authenticator, LdapLookup lookup) {
        this.authenticator = authenticator;
        this.lookup = lookup;
    }

    public Identity authenticate(String username, String password) {
        DirContextOperations entry;
        try {
            entry = authenticator.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (InternalAuthenticationServiceException e) {
            throw new Unavailable(e);
        } catch (AuthenticationException | IncorrectResultSizeDataAccessException e) {
            // a wrong password, an unknown user and a username that matches several entries all look the same
            throw new Rejected(WRONG);
        } catch (org.springframework.ldap.NamingException e) {
            throw new Unavailable(e);
        }
        String mail = text(entry.getStringAttribute(lookup.mailAttribute()));
        if (mail == null) {
            throw new Rejected(NO_MAIL);
        }
        if (mail.equalsIgnoreCase(AccessService.BREAKGLASS_EMAIL)) { // only the break-glass login is that identity
            throw new Rejected(WRONG);
        }
        String name = text(entry.getStringAttribute(lookup.nameAttribute()));
        return new Identity(mail, name != null ? name : mail);
    }

    private static @Nullable String text(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
