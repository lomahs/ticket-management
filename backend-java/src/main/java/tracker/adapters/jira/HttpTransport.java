package tracker.adapters.jira;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Sends one HTTP request. The reader uses the JDK client; tests plug in canned responses. */
@FunctionalInterface
public interface HttpTransport {
    record Response(int status, Map<String, String> headers, String body) {

        /** Header value, case-insensitively, or null. */
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
    }

    /** @param body JSON for POST, null for GET */
    Response send(String method, URI uri, Map<String, String> headers, String body) throws IOException;
}
