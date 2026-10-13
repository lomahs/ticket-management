package tracker.adapters.jira;

import java.util.Map;

/** Standard reason phrases (the JDK client does not expose the server's). */
final class HttpReasons {
    private HttpReasons() {
    }

    private static final Map<Integer, String> PHRASES = Map.ofEntries(
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"), Map.entry(409, "Conflict"),
            Map.entry(410, "Gone"), Map.entry(413, "Request Entity Too Large"), Map.entry(415, "Unsupported Media Type"),
            Map.entry(422, "Unprocessable Entity"), Map.entry(429, "Too Many Requests"),
            Map.entry(500, "Internal Server Error"), Map.entry(501, "Not Implemented"), Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"), Map.entry(504, "Gateway Timeout"));

    static String phrase(int status) {
        return PHRASES.getOrDefault(status, "");
    }
}
