package tracker.adapters.web.auth;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.authentication.LdapAuthenticator;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;

/**
 * Sign-in against Active Directory without a service account: binds as {@code username@domain} (the user principal
 * name; a username that already ends with {@code @domain} is kept), then reads the user's own entry, found by that
 * principal name, over the user's own connection. Bad credentials, unknown users and locked or expired accounts all
 * fail as a bad login; a directory that cannot be reached fails as unavailable ({@link LdapLogin}).
 */
public final class ActiveDirectoryAuthenticator implements LdapAuthenticator {
    private final ActiveDirectoryLdapAuthenticationProvider provider;

    /**
     * @param domain      the user principal name suffix, e.g. {@code company.local}
     * @param urls        the directory URLs, space-separated
     * @param base        where the user's entry is searched; null: from the domain ({@code dc=company,dc=local})
     * @param environment extra JNDI environment, e.g. the connect and read timeouts
     */
    public ActiveDirectoryAuthenticator(String domain, String urls, @Nullable String base, Map<String, String> environment) {
        provider = new ActiveDirectoryLdapAuthenticationProvider(domain, urls, base);
        if (!environment.isEmpty()) { // the provider refuses an empty map
            provider.setContextEnvironmentProperties(new HashMap<>(environment));
        }
        provider.setUserDetailsContextMapper(new EntryMapper());
    }

    @Override
    public DirContextOperations authenticate(Authentication authentication) {
        return ((Entry) provider.authenticate(authentication).getPrincipal()).entry();
    }

    /** Keeps the directory entry itself: {@link LdapLogin} reads the e-mail and name from it. */
    private record Entry(DirContextOperations entry, String getUsername) implements UserDetails {
        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public String getPassword() {
            return "";
        }
    }

    private static final class EntryMapper implements UserDetailsContextMapper {
        @Override
        public UserDetails mapUserFromContext(DirContextOperations ctx, String username, Collection<? extends GrantedAuthority> authorities) {
            return new Entry(ctx, username);
        }

        @Override
        public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
            throw new UnsupportedOperationException();
        }
    }
}
