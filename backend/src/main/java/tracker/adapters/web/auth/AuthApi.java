package tracker.adapters.web.auth;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tracker.adapters.web.ApiProblem;
import tracker.adapters.web.auth.Open.Rule;
import tracker.application.service.AccessService;
import tracker.application.service.SystemService.Mode;
import tracker.domain.AccessStatus;
import tracker.domain.Feature;
import tracker.domain.Identity;
import tracker.domain.Level;
import tracker.domain.Role;
import tracker.domain.Viewer;

/** Sign-in (LDAP, or the break-glass account), sign-out, the signed-in user and their access request. */
@RestController
@RequestMapping("/api/v1")
public class AuthApi {
    private static final Logger log = LoggerFactory.getLogger(AuthApi.class);
    private static final String BREAKGLASS_NAME = "Break-glass admin";

    private final AccessService access;
    private final LdapLogin ldap;
    private final LoginThrottle throttle;
    private final BreakGlass breakGlass;
    private final Optional<DemoLogins> demo;
    private final Mode mode;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;
    private final PasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AuthApi(AccessService access, LdapLogin ldap, LoginThrottle throttle, BreakGlass breakGlass, Optional<DemoLogins> demo,
                   Mode mode, SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) {
        this.access = access;
        this.ldap = ldap;
        this.throttle = throttle;
        this.breakGlass = breakGlass;
        this.demo = demo;
        this.mode = mode;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    /**
     * @param mode         the deployment: {@code demo} or {@code jira}
     * @param demoPassword the password of every demo user; null outside the demo
     * @param demoUsers    the demo usernames to show on the login page; empty outside the demo
     */
    public record AuthInfo(String mode, @Nullable String demoPassword, List<String> demoUsers) {
    }

    public record LoginIn(@NotBlank @Size(max = 256) String username, @NotBlank @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "LoginIn[username=" + username + ", password=***]";
        }
    }

    /** @param note why they need access, for the admin */
    public record AccessRequestIn(@Nullable @Size(max = 500) String note) {
    }

    /**
     * The signed-in user.
     *
     * @param permissions what they can do per feature now: none for anyone without access, write everywhere for an admin
     */
    public record Me(String email, String name, @Nullable String memberId, @Nullable Role role, boolean admin, AccessStatus access,
                     Map<Feature, Level> permissions) {
        static Me of(Viewer v) {
            Map<Feature, Level> levels = new EnumMap<>(Feature.class);
            for (Feature f : Feature.values()) {
                levels.put(f, v.can(f, Level.WRITE) ? Level.WRITE : v.can(f, Level.READ) ? Level.READ : Level.NONE);
            }
            return new Me(v.email(), v.name(), v.memberId(), v.role(), v.admin(), v.access(), levels);
        }
    }

    @Open(Rule.PUBLIC)
    @GetMapping("/auth/info")
    public AuthInfo info() {
        return new AuthInfo(mode.name(), demo.map(DemoLogins::password).orElse(null), demo.map(DemoLogins::users).orElse(List.of()));
    }

    /**
     * Checks the password (the break-glass account when that is the username and it is configured, else the directory),
     * then starts a new session for the user. Wrong credentials answer 401 {@code bad_credentials}; after 5 failures in 15
     * minutes the username is locked for 15 minutes (429 {@code too_many_attempts}); a directory that cannot be reached
     * answers 503 {@code ldap_unavailable}.
     */
    @Open(Rule.PUBLIC)
    @PostMapping("/auth/login")
    public Me login(@RequestBody @Valid LoginIn body, HttpServletRequest request, HttpServletResponse response) {
        String username = body.username().strip();
        if (throttle.locked(username)) {
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", "Too many failed sign-ins. Try again in 15 minutes.");
        }
        Identity id;
        try {
            id = authenticate(username, body.password(), request);
        } catch (LdapLogin.Rejected e) {
            throttle.failed(username);
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "bad_credentials", e.getMessage());
        } catch (LdapLogin.Unavailable e) {
            log.warn("sign-in failed: the directory cannot be reached ({})", e.getCause().toString());
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "ldap_unavailable",
                    "The company directory cannot be reached. Try again later.");
        }
        throttle.succeeded(username);

        // a new session id at sign-in (no session fixation), and a new CSRF token with it
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(id, null, List.of()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
        return Me.of(access.onLogin(id));
    }

    private Identity authenticate(String username, String password, HttpServletRequest request) {
        if (breakGlass.enabled() && username.toLowerCase(Locale.ROOT).equals(BreakGlass.USERNAME)) {
            if (!bcrypt.matches(password, breakGlass.passwordHash())) {
                log.warn("break-glass sign-in REFUSED (wrong password) from {}", request.getRemoteAddr());
                throw new LdapLogin.Rejected(LdapLogin.WRONG);
            }
            log.warn("break-glass sign-in USED from {}", request.getRemoteAddr());
            return new Identity(AccessService.BREAKGLASS_EMAIL, BREAKGLASS_NAME);
        }
        return ldap.authenticate(username, password);
    }

    @Open(Rule.SIGNED_IN)
    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
    }

    @Open(Rule.SIGNED_IN)
    @GetMapping("/me")
    public Me me(Viewer viewer) {
        return Me.of(viewer);
    }

    /** Asks the admin for access; a pending request is kept as it is. */
    @Open(Rule.SIGNED_IN)
    @PostMapping("/me/access-request")
    public Me requestAccess(@RequestBody @Valid AccessRequestIn body) {
        Identity id = ViewerArgumentResolver.identity().orElseThrow();
        return Me.of(access.requestAccess(id, body.note() == null ? "" : body.note().strip()));
    }
}
