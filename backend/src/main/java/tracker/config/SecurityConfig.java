package tracker.config;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.ldap.LdapProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.adapters.web.ApiExceptionHandler;
import tracker.adapters.web.auth.ActiveDirectoryAuthenticator;
import tracker.adapters.web.auth.BreakGlass;
import tracker.adapters.web.auth.LdapLookup;
import tracker.application.service.AccessService;

/**
 * Spring Security: server-side sessions and CSRF protection for the single-page app. Every request passes the filter
 * chain; who may call what is decided per handler by {@code AccessInterceptor} ({@code @Open} / {@code @Requires}). There
 * is no form login and no HTTP Basic: the SPA signs in through {@code POST /api/v1/auth/login}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** The token is in the {@code XSRF-TOKEN} cookie, readable by the SPA, which sends it back in {@code X-XSRF-TOKEN}. */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
                                                   CookieCsrfTokenRepository csrfTokens, ObjectMapper json) throws Exception {
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .securityContext(c -> c.securityContextRepository(contexts))
                .csrf(c -> c.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling(e -> e.accessDeniedHandler(csrfDenied(json)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable());
        return http.build();
    }

    /** A missing or wrong CSRF token: 403 problem details with code {@code csrf}. */
    static AccessDeniedHandler csrfDenied(ObjectMapper json) {
        return (request, response, denied) -> {
            boolean csrf = denied instanceof CsrfException;
            ProblemDetail p = ApiExceptionHandler.problem(HttpStatus.FORBIDDEN, csrf ? "csrf" : "forbidden",
                    csrf ? "The request has no valid CSRF token. Reload the page and try again." : "Forbidden", List.of());
            p.setInstance(java.net.URI.create(request.getRequestURI()));
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), p);
        };
    }

    /**
     * Spring Security's documented handler for single-page apps: a token sent in the header (the raw cookie value) is
     * compared as it is; a token in a form parameter is XOR-masked (BREACH protection).
     */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            xor.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
        }
    }

    /** Loads the deferred CSRF token on every request, so the {@code XSRF-TOKEN} cookie is always set. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    // --- sign-in ----------------------------------------------------------------------------------------------------

    /** Start-up fails on a hash that is not bcrypt ({@link BreakGlass#of}). */
    @Bean
    public BreakGlass breakGlass(TrackerProperties properties) {
        BreakGlass breakGlass = BreakGlass.of(properties.breakglassPasswordHash());
        if (breakGlass.enabled()) {
            log.info("break-glass login is enabled");
        }
        return breakGlass;
    }

    /**
     * Warns at start-up when nobody can approve access requests: no bootstrap admins, no break-glass login and no active
     * admin, e.g. the first deploy on an existing database without {@code TRACKER_ADMINS}.
     */
    @Bean
    public ApplicationRunner approverCheck(AccessService access, BreakGlass breakGlass) {
        return args -> {
            if (!access.hasBootstrapAdmins() && !breakGlass.enabled() && !access.hasActiveAdmin()) {
                log.warn("Nobody can approve access requests: set TRACKER_ADMINS");
            }
        };
    }

    @Bean
    public LdapLookup ldapLookup(TrackerProperties properties) {
        TrackerProperties.Ldap ldap = properties.ldap();
        return new LdapLookup(ldap.userFilter(), ldap.mailAttribute(), ldap.nameAttribute());
    }

    /**
     * With {@code tracker.ldap.ad-domain}: Active Directory without a service account ({@link ActiveDirectoryAuthenticator},
     * on {@code spring.ldap.urls}, {@code spring.ldap.base} and its timeouts). Otherwise binds as the user found by
     * {@code tracker.ldap.user-filter} under {@code spring.ldap.base}, on Boot's LDAP context source ({@code spring.ldap.*},
     * searching as {@code spring.ldap.username} or anonymously; in the demo the embedded server).
     */
    @Bean
    public LdapAuthenticator ldapAuthenticator(TrackerProperties properties, LdapProperties ldap, Environment environment,
                                               BaseLdapPathContextSource contextSource, LdapLookup lookup) {
        String domain = properties.ldap().adDomain();
        if (domain != null) {
            log.info("sign-in: Active Directory, a bind as <username>@{} without a service account", domain);
            return new ActiveDirectoryAuthenticator(domain, String.join(" ", ldap.determineUrls(environment)),
                    StringUtils.hasText(ldap.getBase()) ? ldap.getBase() : null, ldap.getBaseEnvironment());
        }
        FilterBasedLdapUserSearch search = new FilterBasedLdapUserSearch("", lookup.userFilter(), contextSource);
        search.setSearchSubtree(true);
        search.setSearchTimeLimit(10_000);
        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserSearch(search);
        return authenticator;
    }
}
