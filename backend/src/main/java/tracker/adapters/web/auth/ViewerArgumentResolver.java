package tracker.adapters.web.auth;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import tracker.application.AppError.Unauthenticated;
import tracker.application.service.AccessService;
import tracker.domain.Identity;
import tracker.domain.Viewer;

/**
 * The signed-in user of a request: the session's {@link Identity}, and the {@link Viewer} it is now (looked up once per
 * request, so a change by the admin applies from the next request on). A handler gets it as a {@code Viewer} parameter.
 */
@Component
public class ViewerArgumentResolver implements HandlerMethodArgumentResolver {
    private static final String ATTRIBUTE = ViewerArgumentResolver.class.getName() + ".viewer";

    private final AccessService access;

    public ViewerArgumentResolver(AccessService access) {
        this.access = access;
    }

    /** The identity of the session, if someone is signed in. */
    public static Optional<Identity> identity() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Identity id ? Optional.of(id) : Optional.empty();
    }

    /** The viewer of this request; {@link Unauthenticated} when nobody is signed in. */
    public Viewer viewer(RequestAttributes request) {
        if (request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Viewer cached) {
            return cached;
        }
        Identity id = identity().orElseThrow(() -> new Unauthenticated("Sign in first"));
        Viewer v = access.viewer(id);
        request.setAttribute(ATTRIBUTE, v, RequestAttributes.SCOPE_REQUEST);
        return v;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Viewer.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mav, NativeWebRequest request,
                                  @Nullable WebDataBinderFactory binderFactory) {
        return viewer(request);
    }
}
