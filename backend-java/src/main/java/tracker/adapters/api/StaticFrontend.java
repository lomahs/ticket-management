package tracker.adapters.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the built frontend (when its directory has an index.html): files by path, and index.html for every other
 * route so the single-page app can handle it. Unknown /api paths stay 404.
 */
@RestController
public class StaticFrontend {
    private final Path dir;

    public StaticFrontend(StaticDir staticDir) {
        Path d = staticDir.path();
        this.dir = d != null && Files.isRegularFile(d.resolve("index.html")) ? d.toAbsolutePath().normalize() : null;
    }

    /** The directory of the built frontend, or a null path for none. */
    public record StaticDir(Path path) {
    }

    @GetMapping({"/", "/{*path}"})
    public ResponseEntity<Object> serve(@PathVariable(name = "path", required = false) String path) throws IOException {
        String p = path == null ? "" : path.replaceFirst("^/+", "");
        if (dir == null || p.equals("api") || p.startsWith("api/")) {
            return notFound();
        }
        Path f = dir.resolve(p).normalize();
        boolean inside = f.startsWith(dir);
        if (!p.isEmpty() && inside && Files.isRegularFile(f)) {
            return file(f);
        }
        if (p.startsWith("assets/")) {
            return notFound(); // a missing asset is a 404, not the app
        }
        return file(dir.resolve("index.html"));
    }

    private static ResponseEntity<Object> file(Path f) {
        MediaType type = MediaTypeFactory.getMediaType(f.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(f));
    }

    private static ResponseEntity<Object> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(Map.of("detail", "Not Found"));
    }
}
