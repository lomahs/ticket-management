package tracker.adapters.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built frontend ({@code tracker.static-dir}) when it has an index.html: its files by path, and index.html for
 * every other path so the single-page app can route it. Unknown /api paths and missing assets stay 404.
 */
@Configuration(proxyBeanMethods = false)
class SpaConfig implements WebMvcConfigurer {
    private final Path dir;

    SpaConfig(@Value("${tracker.static-dir:../frontend/dist}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    private boolean present() {
        return Files.isRegularFile(dir.resolve("index.html"));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (present()) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!present()) {
            return;
        }
        Resource index = new FileSystemResource(dir.resolve("index.html"));
        registry.addResourceHandler("/**").addResourceLocations(new FileSystemResource(dir.toString() + "/")).resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource file = super.getResource(path, location);
                        if (file != null || path.startsWith("api/") || path.equals("api") || path.startsWith("assets/")) {
                            return file;
                        }
                        return index;
                    }
                });
    }
}
