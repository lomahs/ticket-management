package tracker.adapters.web.auth;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The access rule of every API handler, and the {@code Viewer} handler parameter. */
@Configuration(proxyBeanMethods = false)
class AuthWebConfig implements WebMvcConfigurer {
    private final AccessInterceptor interceptor;
    private final ViewerArgumentResolver viewers;

    AuthWebConfig(AccessInterceptor interceptor, ViewerArgumentResolver viewers) {
        this.interceptor = interceptor;
        this.viewers = viewers;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // every path, not only /api/**: the interceptor itself picks the application's handlers, so no path spelling skips it
        registry.addInterceptor(interceptor);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(viewers);
    }
}
