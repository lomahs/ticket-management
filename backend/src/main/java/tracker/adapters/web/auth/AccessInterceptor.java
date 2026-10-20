package tracker.adapters.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import tracker.application.AppError.Forbidden;
import tracker.application.AppError.NoAccess;
import tracker.application.AppError.Unauthenticated;
import tracker.domain.Viewer;

/**
 * Applies the {@link Open} or {@link Requires} rule of every handler of the application (all of them are under
 * {@code /api}) before it runs: nobody signed in →
 * {@link Unauthenticated} (401), signed in without access → {@link NoAccess} (403 {@code no_access}), not allowed →
 * {@link Forbidden} (403). The errors are rendered by the API's exception handler like any other. A handler without a
 * rule is refused (fail closed).
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AccessInterceptor.class);

    private final ViewerArgumentResolver viewers;

    public AccessInterceptor(ViewerArgumentResolver viewers) {
        this.viewers = viewers;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !method.getBeanType().getPackageName().startsWith("tracker.")) {
            return true; // static files, unknown paths (404), the OpenAPI document, the error page
        }
        Open open = method.getMethodAnnotation(Open.class);
        Requires requires = method.getMethodAnnotation(Requires.class);
        if ((open == null) == (requires == null)) {
            log.error("{} declares {} access rule; refusing it", method.getShortLogMessage(), open == null ? "no" : "more than one");
            throw new Forbidden("This endpoint has no access rule");
        }
        if (open != null && open.value() == Open.Rule.PUBLIC) {
            return true;
        }
        if (ViewerArgumentResolver.identity().isEmpty()) {
            throw new Unauthenticated("Sign in first");
        }
        if (open != null && open.value() == Open.Rule.SIGNED_IN) {
            return true;
        }
        Viewer viewer = viewers.viewer(new ServletRequestAttributes(request, response));
        if (!viewer.active()) {
            throw new NoAccess("You don't have access to Team Tracker");
        }
        if (open != null) {
            if (open.value() == Open.Rule.ADMIN && !viewer.admin()) {
                throw new Forbidden("Only an admin can do this");
            }
            return true;
        }
        if (!viewer.can(requires.feature(), requires.level())) {
            throw new Forbidden("You need " + requires.level() + " access to " + requires.feature());
        }
        return true;
    }
}
