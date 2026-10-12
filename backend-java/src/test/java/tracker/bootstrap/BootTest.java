package tracker.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.adapters.api.StaticFrontend;

/** The real Spring Boot application in demo mode, serving a built frontend, on a random port. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootTest {
    static final List<String> PROPS = List.of("TRACKER_MODE", "tracker.home", "TRACKER_STATIC_DIR");
    ConfigurableApplicationContext ctx;
    String base;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void start() throws Exception {
        Path root = Files.createTempDirectory("tracker-boot");
        Path dist = Files.createDirectories(root.resolve("frontend").resolve("dist").resolve("assets")).getParent();
        Files.writeString(dist.resolve("index.html"), "<!doctype html><title>Team Tracker</title>");
        Files.writeString(dist.resolve("assets").resolve("app.js"), "console.log('app')");
        System.setProperty("TRACKER_MODE", "demo");
        System.setProperty("tracker.home", root.resolve("backend-java").toString());
        System.clearProperty("TRACKER_STATIC_DIR");
        ctx = SpringApplication.run(TrackerApplication.class, "--server.port=0");
        base = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    void stop() {
        if (ctx != null) {
            ctx.close();
        }
        PROPS.forEach(System::clearProperty);
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheApiInDemoMode() throws Exception {
        HttpResponse<String> r = get("/api/v1/meta");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        JsonNode m = new ObjectMapper().readTree(r.body());
        assertThat(m.get("mode").asText()).isEqualTo("demo");
        assertThat(m.get("sync").get("status").asText()).isEqualTo("ok");
        assertThat(new ObjectMapper().readTree(get("/api/v1/anken").body())).hasSize(6);
    }

    @Test
    void servesTheBuiltFrontendWithSpaFallback() throws Exception {
        assertThat(get("/").body()).contains("Team Tracker");
        assertThat(get("/anken/PAY-1100/matrix?prog=late").body()).contains("Team Tracker");
        HttpResponse<String> js = get("/assets/app.js");
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(js.headers().firstValue("content-type").orElse("")).contains("javascript");
        assertThat(get("/assets/missing.js").statusCode()).isEqualTo(404);
        HttpResponse<String> api = get("/api/v1/nope");
        assertThat(api.statusCode()).isEqualTo(404);
        assertThat(api.body()).isEqualTo("{\"detail\":\"Not Found\"}");
        assertThat(get("/../../etc/passwd").body()).doesNotContain("root:");
    }

    @Test
    void uploadsAboveTheServletLimitGetTheApiMessage() throws Exception {
        String boundary = "b0undary";
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + 8 * 1024 * 1024 + tail.length];
        java.util.Arrays.fill(body, (byte) 'x');
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(tail, 0, body, body.length - tail.length, tail.length);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/leaves/import/preview"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("The file is larger than 5 MB");
    }

    /** Every operation of the frontend's API contract (frontend/src/shared/api/openapi.json) is served by a controller. */
    @Test
    void servesEveryOperationOfTheFrontendContract() throws Exception {
        Path contract = Path.of("..", "frontend", "src", "shared", "api", "openapi.json");
        assertThat(contract).as("the frontend's API contract").exists();
        JsonNode doc = new ObjectMapper().readTree(contract.toFile());
        RequestMappingHandlerMapping mapping = ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        List<String> missing = new ArrayList<>();
        int operations = 0;
        Iterator<Map.Entry<String, JsonNode>> paths = doc.get("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> p = paths.next();
            String path = p.getKey().replaceAll("\\{[^}]+}", "X-1");
            Iterator<String> methods = p.getValue().fieldNames();
            while (methods.hasNext()) {
                String method = methods.next().toUpperCase(java.util.Locale.ROOT);
                operations++;
                HandlerExecutionChain chain = mapping.getHandler(new MockHttpServletRequest(method, path));
                boolean served = chain != null && chain.getHandler() instanceof HandlerMethod h
                        && !StaticFrontend.class.isAssignableFrom(h.getBeanType());
                if (!served) {
                    missing.add(method + " " + p.getKey());
                }
            }
        }
        assertThat(operations).isGreaterThan(40);
        assertThat(missing).as("operations without a controller").isEmpty();
    }
}
