package tracker.adapters.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** problem+json error responses (RFC 9457) with a stable {@code code} and per-field {@code errors}. */
final class Problem {
    private Problem() {
    }

    static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    static ResponseEntity<Object> of(int status, String code, String detail, List<RequestInvalid.FieldError> errors) {
        String words = code.replace("_", " ");
        String title = words.isEmpty() ? words : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1).toLowerCase(Locale.ROOT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("code", code);
        body.put("detail", detail);
        body.put("errors", errors.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", e.field());
            m.put("message", e.message());
            return m;
        }).toList());
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
    }
}
