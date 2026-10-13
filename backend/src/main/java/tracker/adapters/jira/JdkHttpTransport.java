package tracker.adapters.jira;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** HttpTransport over java.net.http. */
public final class JdkHttpTransport implements HttpTransport {
    private final HttpClient client;
    private final Duration timeout;

    public JdkHttpTransport(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Response send(String method, URI uri, Map<String, String> headers, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> h = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> h.put(k, v.isEmpty() ? "" : v.get(0)));
            return new Response(r.statusCode(), h, r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
