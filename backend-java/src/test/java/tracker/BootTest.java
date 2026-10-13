package tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The packaged application in the demo profile on a random port, serving a built frontend. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:sqlite:target/test-db/${random.uuid}.db?foreign_keys=on&journal_mode=WAL&busy_timeout=5000"
                + "&transaction_mode=IMMEDIATE")
@ActiveProfiles("demo")
class BootTest {
    static final ObjectMapper JSON = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void builtFrontend(DynamicPropertyRegistry registry) throws IOException {
        Path dist = Files.createTempDirectory("tracker-dist");
        Files.createDirectories(dist.resolve("assets"));
        Files.writeString(dist.resolve("index.html"), "<!doctype html><title>Team Tracker</title>");
        Files.writeString(dist.resolve("assets").resolve("app.js"), "console.log('app')");
        registry.add("tracker.static-dir", dist::toString);
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheApiInDemoMode() throws Exception {
        HttpResponse<String> r = get("/api/v1/meta");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("content-type").orElse("")).startsWith("application/json");
        JsonNode m = JSON.readTree(r.body());
        assertThat(m.get("mode").asText()).isEqualTo("demo");
        assertThat(m.get("sync").get("status").asText()).isEqualTo("ok");
        assertThat(JSON.readTree(get("/api/v1/anken").body())).hasSize(6);
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
        assertThat(api.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
        assertThat(get("/../../etc/passwd").body()).doesNotContain("root:");
    }

    @Test
    void uploadsAboveTheServletLimitAreRefused() throws Exception {
        String boundary = "b0undary";
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + 8 * 1024 * 1024 + tail.length];
        java.util.Arrays.fill(body, (byte) 'x');
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(tail, 0, body, body.length - tail.length, tail.length);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/leaves/import/preview"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.body()).contains("The file is larger than 5 MB");
    }

    /**
     * The frontend's API types are generated from frontend/src/shared/api/openapi.json, which must be this application's
     * OpenAPI document. {@code npm run gen:api} (or {@code ./mvnw test -Dtest=BootTest -Dopenapi.write=true}) rewrites it.
     */
    @Test
    void theFrontendContractIsTheOpenApiDocumentOfTheApp() throws Exception {
        HttpResponse<String> r = get("/openapi.json");
        assertThat(r.statusCode()).isEqualTo(200);
        String doc = r.body().strip() + "\n";
        JsonNode parsed = JSON.readTree(doc);
        assertThat(parsed.get("paths").size()).isGreaterThan(30);
        assertThat(parsed.get("paths").has("/")).isFalse(); // only /api is documented
        Path contract = Path.of("..", "frontend", "src", "shared", "api", "openapi.json");
        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(contract, doc);
        }
        assertThat(contract).as("the frontend's API contract").exists();
        assertThat(Files.readString(contract)).as("frontend/src/shared/api/openapi.json is out of date: run npm run gen:api")
                .isEqualTo(doc);
    }
}
